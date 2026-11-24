import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path
from unittest.mock import patch

import services
from database import Database
from google_calendar import GoogleCalendarError, GoogleCalendarEventIdGone


class FakeCalendar:
    def __init__(self):
        self.created = []
        self.updated = []
        self.deleted = []

    def create_event(self, **kwargs):
        self.created.append(kwargs)
        return f"created-{len(self.created)}"

    def update_event(self, **kwargs):
        self.updated.append(kwargs)

    def delete_event(self, calendar_id, google_event_id):
        self.deleted.append((calendar_id, google_event_id))


class FlakyCreateCalendar(FakeCalendar):
    def __init__(self):
        super().__init__()
        self.attempted_ids = []
        self.fail_once = True

    def create_event(self, **kwargs):
        self.attempted_ids.append(kwargs.get("event_id"))
        if self.fail_once:
            self.fail_once = False
            raise GoogleCalendarError("response lost")
        self.created.append(kwargs)
        return kwargs["event_id"]


class PartialDeleteCalendar(FakeCalendar):
    def __init__(self):
        super().__init__()
        self.remote_ids = {"one", "two"}
        self.failed_once = False

    def delete_event(self, calendar_id, google_event_id):
        self.deleted.append((calendar_id, google_event_id))
        if google_event_id == "two" and not self.failed_once:
            self.failed_once = True
            raise GoogleCalendarError("temporary delete failure")
        self.remote_ids.discard(google_event_id)  # deleting an already-absent ID is idempotent


class TombstonedCreateCalendar(FakeCalendar):
    def create_event(self, **kwargs):
        self.created.append(kwargs)
        if kwargs.get("event_id") == "deleted-owned":
            raise GoogleCalendarEventIdGone("deleted tombstone")
        return kwargs["event_id"]


class CalendarSegmentTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        path = Path(self.tempdir.name) / "test.db"
        self.db = Database(f"sqlite:///{path.as_posix()}")
        self.db.execute("INSERT INTO users (user_id,email,display_name) VALUES ('u','u@x.com','U')")
        self.db.execute(
            "INSERT INTO plans (user_id,plan_date,raw_input) VALUES ('u',?,'x')",
            ((date.today() + timedelta(days=1)).isoformat(),),
        )
        self.db.connection.commit()
        self.plan = self.db.fetch_one("SELECT plan_id FROM plans")["plan_id"]
        self.fake = FakeCalendar()

    def tearDown(self):
        self.db.close()
        self.tempdir.cleanup()

    def test_imported_and_legacy_ids_are_never_updated(self):
        imported = self.db.add_task(
            self.plan, "u", "Imported", estimated_minutes=60,
            scheduled_start="09:00", scheduled_end="10:00", is_fixed_time=True,
            calendar_provenance="imported", google_event_id="external",
            google_calendar_id="team",
        )
        legacy = self.db.add_task(
            self.plan, "u", "Legacy", estimated_minutes=60,
            scheduled_start="10:00", scheduled_end="11:00",
            calendar_provenance="legacy_unknown", google_event_id="unknown",
        )
        with patch.object(services, "get_database", return_value=self.db), patch.object(
            services, "_get_google_client", return_value=self.fake
        ), patch.object(services, "get_user_timezone", return_value="Africa/Cairo"):
            self.assertTrue(services.export_task_to_google_calendar(imported, "u"))
            self.assertTrue(services.export_task_to_google_calendar(legacy, "u"))
        self.assertEqual([], self.fake.updated)
        self.assertEqual([], self.fake.created)

    def test_one_event_is_created_per_segment_and_marked_owned(self):
        task = self.db.add_task(self.plan, "u", "Split", estimated_minutes=60)
        revision = self.db.get_plan_by_id(self.plan, "u")["schedule_revision"]
        self.db.replace_plan_segments(self.plan, "u", {task: [
            {"segment_index": 0, "scheduled_start": "09:00:00", "scheduled_end": "09:30:00"},
            {"segment_index": 1, "scheduled_start": "10:00:00", "scheduled_end": "10:30:00"},
        ]}, revision)
        with patch.object(services, "get_database", return_value=self.db), patch.object(
            services, "_get_google_client", return_value=self.fake
        ), patch.object(services, "get_user_timezone", return_value="Africa/Cairo"):
            self.assertTrue(services.export_task_to_google_calendar(task, "u"))
        self.assertEqual(2, len(self.fake.created))
        segments = self.db.get_task_segments(task, "u")
        self.assertTrue(all(row["calendar_provenance"] == "coachai_created" for row in segments))

    def test_export_is_idempotent_and_failed_create_retries_same_identity(self):
        task = self.db.add_task(self.plan, "u", "Retry", estimated_minutes=60)
        revision = self.db.get_plan_by_id(self.plan, "u")["schedule_revision"]
        self.db.replace_plan_segments(self.plan, "u", {task: [{
            "segment_index": 0, "scheduled_start": "09:00:00", "scheduled_end": "10:00:00",
        }]}, revision)
        flaky = FlakyCreateCalendar()
        with patch.object(services, "get_database", return_value=self.db), patch.object(
            services, "_get_google_client", return_value=flaky
        ), patch.object(services, "get_user_timezone", return_value="Africa/Cairo"):
            self.assertFalse(services.export_task_to_google_calendar(task, "u"))
            failed = self.db.get_task_segments(task, "u")[0]
            self.assertEqual("create_error", failed["calendar_sync_status"])
            self.assertTrue(services.export_task_to_google_calendar(task, "u"))
            self.assertTrue(services.export_task_to_google_calendar(task, "u"))
        self.assertEqual(flaky.attempted_ids[0], flaky.attempted_ids[1])
        self.assertEqual(1, len(flaky.created))
        self.assertEqual(1, len(flaky.updated))

    def test_tombstoned_event_id_rotates_once_and_persists_replacement(self):
        task = self.db.add_task(self.plan, "u", "Restore", estimated_minutes=60)
        revision = self.db.get_plan_by_id(self.plan, "u")["schedule_revision"]
        self.db.replace_plan_segments(self.plan, "u", {task: [{
            "segment_index": 0, "scheduled_start": "09:00:00", "scheduled_end": "10:00:00",
            "google_event_id": "deleted-owned", "google_calendar_id": "primary",
            "calendar_provenance": "coachai_created", "calendar_sync_status": "pending_create",
        }]}, revision)
        calendar = TombstonedCreateCalendar()
        with patch.object(services, "get_database", return_value=self.db), patch.object(
            services, "get_user_timezone", return_value="Africa/Cairo"
        ):
            self.assertTrue(services.export_task_to_google_calendar(task, "u", client=calendar))
        restored = self.db.get_task_segments(task, "u")[0]
        self.assertNotEqual("deleted-owned", restored["google_event_id"])
        self.assertEqual(calendar.created[-1]["event_id"], restored["google_event_id"])
        self.assertEqual("synced", restored["calendar_sync_status"])

    def test_orphan_cleanup_deletes_only_coachai_created(self):
        task = self.db.add_task(self.plan, "u", "Task", estimated_minutes=60)
        revision = self.db.get_plan_by_id(self.plan, "u")["schedule_revision"]
        first = self.db.replace_plan_segments(self.plan, "u", {task: [
            {"segment_index": 0, "scheduled_start": "09:00:00", "scheduled_end": "09:30:00",
             "google_event_id": "owned", "google_calendar_id": "primary",
             "calendar_provenance": "coachai_created"},
            {"segment_index": 1, "scheduled_start": "10:00:00", "scheduled_end": "10:30:00",
             "google_event_id": "external", "google_calendar_id": "team",
             "calendar_provenance": "imported"},
        ]}, revision)
        revision = self.db.get_plan_by_id(self.plan, "u")["schedule_revision"]
        second = self.db.replace_plan_segments(self.plan, "u", {task: []}, revision)
        with patch.object(services, "get_database", return_value=self.db):
            result = services.reconcile_schedule_calendar_operation(second, "u", client=self.fake)
        self.assertEqual(1, result["deleted"])
        self.assertEqual([("primary", "owned")], self.fake.deleted)

    def test_rescheduled_owned_segment_preserves_identity_and_becomes_stale(self):
        task = self.db.add_task(self.plan, "u", "Owned", estimated_minutes=60)
        revision = self.db.get_plan_by_id(self.plan, "u")["schedule_revision"]
        self.db.replace_plan_segments(self.plan, "u", {task: [{
            "segment_index": 0, "scheduled_start": "09:00:00", "scheduled_end": "10:00:00",
            "google_event_id": "owned", "google_calendar_id": "primary",
            "calendar_provenance": "coachai_created", "calendar_sync_status": "synced",
        }]}, revision)
        revision = self.db.get_plan_by_id(self.plan, "u")["schedule_revision"]
        self.db.replace_plan_segments(self.plan, "u", {task: [{
            "segment_index": 0, "scheduled_start": "11:00:00", "scheduled_end": "12:00:00",
        }]}, revision)
        segment = self.db.get_task_segments(task, "u")[0]
        self.assertEqual("owned", segment["google_event_id"])
        self.assertEqual("pending_update", segment["calendar_sync_status"])
        self.assertEqual(1, self.db.count_stale_segment_exports(self.plan, "u"))
        with patch.object(services, "get_database", return_value=self.db), patch.object(
            services, "_get_google_client", return_value=self.fake
        ), patch.object(services, "get_user_timezone", return_value="Africa/Cairo"):
            self.assertTrue(services.export_task_to_google_calendar(task, "u"))
        self.assertEqual(1, len(self.fake.updated))

    def test_operation_undo_cleans_up_new_owned_event(self):
        task = self.db.add_task(self.plan, "u", "Owned", estimated_minutes=60)
        revision = self.db.get_plan_by_id(self.plan, "u")["schedule_revision"]
        operation = self.db.replace_plan_segments(self.plan, "u", {task: [{
            "segment_index": 0, "scheduled_start": "09:00:00", "scheduled_end": "10:00:00",
            "google_event_id": "new-owned", "google_calendar_id": "primary",
            "calendar_provenance": "coachai_created", "calendar_sync_status": "synced",
        }]}, revision)
        with patch.object(services, "get_database", return_value=self.db), patch.object(
            services, "_get_google_client", return_value=self.fake
        ):
            self.assertTrue(services.undo_smart_schedule(operation, "u"))
        self.assertEqual([("primary", "new-owned")], self.fake.deleted)
        self.assertEqual([], self.db.get_task_segments(task, "u"))

    def test_operation_undo_cleans_up_event_exported_after_operation_snapshot(self):
        task = self.db.add_task(self.plan, "u", "Owned", estimated_minutes=60)
        revision = self.db.get_plan_by_id(self.plan, "u")["schedule_revision"]
        operation = self.db.replace_plan_segments(self.plan, "u", {task: [{
            "segment_index": 0, "scheduled_start": "09:00:00", "scheduled_end": "10:00:00",
        }]}, revision)
        segment = self.db.get_task_segments(task, "u")[0]
        self.db.update_segment_calendar_state(
            segment["segment_id"], "u", google_event_id="post-apply-owned",
            google_calendar_id="primary", calendar_provenance="coachai_created",
            calendar_sync_status="synced", calendar_sync_error="",
        )

        with patch.object(services, "get_database", return_value=self.db), patch.object(
            services, "_get_google_client", return_value=self.fake
        ):
            self.assertTrue(services.undo_smart_schedule(operation, "u"))

        self.assertEqual([("primary", "post-apply-owned")], self.fake.deleted)
        self.assertEqual([], self.db.get_task_segments(task, "u"))

    def test_operation_undo_recreates_only_restored_owned_segment(self):
        task = self.db.add_task(self.plan, "u", "Moved", estimated_minutes=60)
        revision = self.db.get_plan_by_id(self.plan, "u")["schedule_revision"]
        self.db.replace_plan_segments(self.plan, "u", {task: [{
            "segment_index": 0, "scheduled_start": "09:00:00", "scheduled_end": "10:00:00",
            "google_event_id": "old-owned", "google_calendar_id": "primary",
            "calendar_provenance": "coachai_created", "calendar_sync_status": "synced",
        }]}, revision)
        revision = self.db.get_plan_by_id(self.plan, "u")["schedule_revision"]
        operation = self.db.replace_plan_segments(self.plan, "u", {task: [{
            "segment_index": 0, "scheduled_start": "11:00:00", "scheduled_end": "12:00:00",
        }]}, revision)
        moved = self.db.get_task_segments(task, "u")[0]
        self.db.update_segment_calendar_state(
            moved["segment_id"], "u", google_event_id="new-owned",
            google_calendar_id="primary", calendar_provenance="coachai_created",
            calendar_sync_status="synced", calendar_sync_error="",
        )

        with patch.object(services, "get_database", return_value=self.db), patch.object(
            services, "_get_google_client", return_value=self.fake
        ), patch.object(services, "get_user_timezone", return_value="Africa/Cairo"):
            self.assertTrue(services.undo_smart_schedule(operation, "u"))

        self.assertEqual([("primary", "new-owned")], self.fake.deleted)
        self.assertEqual("old-owned", self.fake.created[0]["event_id"])
        self.assertEqual("09:00:00", self.fake.created[0]["start_time"])

    def test_task_delete_is_durable_and_queues_remote_cleanup(self):
        task = self.db.add_task(self.plan, "u", "Delete", estimated_minutes=60)
        revision = self.db.get_plan_by_id(self.plan, "u")["schedule_revision"]
        self.db.replace_plan_segments(self.plan, "u", {task: [
            {"segment_index": 0, "scheduled_start": "09:00:00", "scheduled_end": "09:30:00",
             "google_event_id": "one", "calendar_provenance": "coachai_created"},
            {"segment_index": 1, "scheduled_start": "10:00:00", "scheduled_end": "10:30:00",
             "google_event_id": "two", "calendar_provenance": "coachai_created"},
        ]}, revision)
        flaky = PartialDeleteCalendar()
        with patch.object(services, "get_database", return_value=self.db), patch.object(
            services, "_get_google_client", return_value=flaky
        ):
            self.assertTrue(services.delete_task(task, "u"))
        self.assertIsNone(self.db.get_task(task, "u"))
        self.assertEqual({"one", "two"}, flaky.remote_ids)
        queued = self.db.fetch_one(
            """SELECT operation_type,status,payload FROM calendar_operations
               WHERE user_id=?""",
            ("u",),
        )
        self.assertIsNotNone(queued)
        self.assertEqual("delete_task_events", queued["operation_type"])
        self.assertEqual("queued", queued["status"])
        self.assertIn('"google_event_id": "one"', queued["payload"])
        self.assertIn('"google_event_id": "two"', queued["payload"])

    def test_self_events_do_not_block_but_external_events_do(self):
        task = self.db.add_task(self.plan, "u", "Owned", estimated_minutes=60)
        revision = self.db.get_plan_by_id(self.plan, "u")["schedule_revision"]
        self.db.replace_plan_segments(self.plan, "u", {task: [{
            "segment_index": 0, "scheduled_start": "09:00:00", "scheduled_end": "10:00:00",
            "google_event_id": "self", "calendar_provenance": "coachai_created",
        }]}, revision)
        events = [
            {"google_event_id": "self", "start_time": "09:00:00", "end_time": "10:00:00"},
            {"google_event_id": "external", "start_time": "11:00:00", "end_time": "12:00:00"},
        ]
        with patch.object(services, "get_database", return_value=self.db), patch.object(
            self.db, "get_today_plan", return_value={"plan_id": self.plan}
        ), patch.object(services, "get_google_calendar_events_today", return_value=events):
            slots = services.get_google_calendar_blocked_slots("u")
        self.assertEqual(1, len(slots))
        self.assertEqual((11, 12), (slots[0][0].hour, slots[0][1].hour))


if __name__ == "__main__":
    unittest.main()
