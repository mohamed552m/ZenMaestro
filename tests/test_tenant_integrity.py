from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
import tempfile
import unittest

from application_services import CoachAIApplication
from backend_errors import AuthorizationDenied, ResourceNotFound
from calendar_security import (
    CalendarOperationQueue,
    EncryptedGoogleTokenStore,
    OAuthStateStore,
    VersionedTokenCipher,
)
from database import Database, OwnershipError
from identity import AuthenticatedPrincipal
from notifications import DomainEvent, NotificationEventType, NotificationRepository, NotificationService


class TenantIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        path = Path(self.tempdir.name) / "tenant-integrity.db"
        self.db = Database(f"sqlite:///{path.as_posix()}")
        self.db.create_user("u1", "one@example.com", "One", "UTC")
        self.db.create_user("u2", "two@example.com", "Two", "UTC")
        self.p1 = AuthenticatedPrincipal("u1", "one@example.com", "One", {})
        self.p2 = AuthenticatedPrincipal("u2", "two@example.com", "Two", {})
        self.app1 = CoachAIApplication(self.db, self.p1)
        self.app2 = CoachAIApplication(self.db, self.p2)
        mutable_day = date.today() + timedelta(days=1)
        self.plan1 = self.db.create_plan("u1", mutable_day, "one")
        self.plan2 = self.db.create_plan("u2", mutable_day, "two")
        self.category1 = self.db.create_category("u1", "Private one", "#112233")
        self.category2 = self.db.create_category("u2", "Private two", "#445566")
        self.task1 = self.app1.tasks.create_task(
            plan_id=self.plan1,
            title="One task",
            category_id=self.category1,
            estimated_minutes=30,
            idempotency_key="u1-task",
        )["task_id"]
        self.task2 = self.app2.tasks.create_task(
            plan_id=self.plan2,
            title="Two task",
            category_id=self.category2,
            estimated_minutes=30,
            idempotency_key="u2-task",
        )["task_id"]

    def tearDown(self):
        self.db.close()
        self.tempdir.cleanup()

    def test_cross_user_plan_category_and_task_references_are_rejected(self):
        with self.assertRaises(ResourceNotFound):
            self.app1.tasks.create_task(
                plan_id=self.plan2,
                title="Spoofed plan",
                estimated_minutes=30,
                idempotency_key="foreign-plan",
            )
        with self.assertRaises(ResourceNotFound):
            self.app1.tasks.create_task(
                plan_id=self.plan1,
                title="Spoofed category",
                category_id=self.category2,
                estimated_minutes=30,
                idempotency_key="foreign-category",
            )
        with self.assertRaises(ResourceNotFound):
            self.app1.tasks.update_task(
                self.task2,
                expected_version=0,
                idempotency_key="foreign-task",
                changes={"title": "Stolen"},
            )
        self.assertEqual("Two task", self.db.get_task(self.task2, "u2")["title"])
        with self.assertRaises(OwnershipError):
            self.db.add_task(
                self.plan2, "u1", "Direct spoof", estimated_minutes=30
            )

    def test_favorite_category_cannot_cross_tenants(self):
        with self.assertRaises(OwnershipError):
            self.db.update_profile("u1", favorite_category_id=self.category2)
        profile = self.db.get_profile("u1")
        self.assertIsNone(profile.get("favorite_category_id"))

    def test_schedule_move_and_undo_cannot_cross_tenants(self):
        expected = int(self.db.get_plan_by_id(self.plan2, "u2")["schedule_revision"])
        scheduled = self.app2.scheduling.schedule_plan(
            self.plan2,
            expected_revision=expected,
            work_day_start=time(9),
            idempotency_key="u2-schedule",
        )
        with self.assertRaises(ResourceNotFound):
            self.app1.scheduling.move_task(
                self.task2,
                expected_schedule_revision=int(
                    self.db.get_plan_by_id(self.plan2, "u2")["schedule_revision"]
                ),
                new_start=time(11),
                idempotency_key="foreign-move",
            )
        with self.assertRaises(ResourceNotFound):
            self.app1.scheduling.undo(
                scheduled["operation_id"], idempotency_key="foreign-undo"
            )

    def test_calendar_event_and_segment_ids_cannot_cross_tenants(self):
        foreign_task = self.db.add_task(
            self.plan2,
            "u2",
            "Foreign Calendar event",
            estimated_minutes=60,
            scheduled_start="12:00",
            scheduled_end="13:00",
            google_event_id="foreign-event",
            google_calendar_id="foreign-calendar",
            calendar_provenance="coachai_created",
        )
        foreign_segment = self.db.get_task_segments(foreign_task, "u2")[0]
        with self.assertRaises(ResourceNotFound):
            self.app1.calendar.queue_mutation(
                operation_type="event.delete",
                payload={
                    "event_id": "foreign-event",
                    "calendar_id": "foreign-calendar",
                },
                idempotency_key="foreign-delete",
            )
        with self.assertRaises(ResourceNotFound):
            self.app1.calendar.queue_mutation(
                operation_type="event.upsert",
                payload={
                    "task_id": self.task1,
                    "segment_ids": [foreign_segment["segment_id"]],
                },
                idempotency_key="foreign-segment",
            )
        self.assertEqual(
            [], self.db.fetch_all("SELECT * FROM calendar_operations WHERE user_id='u1'")
        )

    def test_notification_devices_actions_and_acknowledgements_are_owner_scoped(self):
        repo = NotificationRepository(self.db)
        repo.register_device(
            "u2", device_id="u2-phone", platform="android", provider="fcm",
            push_token="private-u2-token",
        )
        for action in (
            lambda: self.app1.notifications.invalidate_device(
                "u2-phone", idempotency_key="foreign-invalidate"
            ),
            lambda: self.app1.notifications.acknowledge_schedule_revision(
                "u2-phone", 0, idempotency_key="foreign-ack"
            ),
            lambda: self.app1.notifications.local_schedules(device_id="u2-phone"),
            lambda: self.app1.notifications.register_device(
                device_id="u2-phone", platform="android", provider="fcm",
                push_token="replacement", idempotency_key="foreign-register",
            ),
        ):
            with self.assertRaises(ResourceNotFound):
                action()
        stored = repo.get_device("u2", "u2-phone")
        self.assertEqual("private-u2-token", stored["push_token"])

    def test_notification_read_and_acted_commands_do_not_enumerate_foreign_rows(self):
        service = NotificationService(self.db)
        notification = service.process_event(
            DomainEvent.create(
                user_id="u2",
                event_type=NotificationEventType.TASK_UNPLACED,
                payload={"task_title": "Private"},
                idempotency_key="u2-private-notification",
            )
        )
        self.db.commit()
        notification_id = notification["notification_id"]
        with self.assertRaises(ResourceNotFound):
            self.app1.notifications.mark_read(
                notification_id, idempotency_key="foreign-read"
            )
        with self.assertRaises(ResourceNotFound):
            self.app1.notifications.mark_acted(
                notification_id, idempotency_key="foreign-acted"
            )
        stored = NotificationRepository(self.db).get_notification(
            "u2", notification_id
        )
        self.assertIsNone(stored.get("read_at"))
        self.assertIsNone(stored.get("acted_at"))

    def test_calendar_operations_are_principal_scoped_for_get_list_and_cancel(self):
        operation, _ = CalendarOperationQueue(self.db).enqueue(
            self.p2,
            operation_type="sync.range",
            idempotency_key="u2-calendar-operation",
            payload={
                "calendar_id": "primary",
                "range_start": "2026-10-09",
                "range_end": "2026-10-10",
            },
        )
        with self.assertRaises(ResourceNotFound):
            self.app1.calendar.get_operation(operation.operation_id)
        with self.assertRaises(ResourceNotFound):
            self.app1.calendar.cancel_operation(
                operation.operation_id, idempotency_key="foreign-cancel"
            )
        self.assertEqual([], self.app1.calendar.list_operations())
        self.assertEqual("queued", self.app2.calendar.get_operation(
            operation.operation_id
        )["status"])

    def test_oauth_state_and_encrypted_tokens_are_principal_scoped(self):
        redirect = "https://app.example.test/oauth/google/callback"
        issued = OAuthStateStore(self.db).issue(self.p2, redirect_uri=redirect)
        with self.assertRaises(AuthorizationDenied):
            OAuthStateStore(self.db).consume(
                self.p1, state=issued.state, redirect_uri=redirect
            )
        cipher = VersionedTokenCipher.from_single_key(
            VersionedTokenCipher.generate_key()
        )
        store = EncryptedGoogleTokenStore(self.db, cipher)
        store.save(
            self.p2,
            access_token="u2-access-secret",
            refresh_token="u2-refresh-secret",
            token_expiry=datetime.now(timezone.utc) + timedelta(hours=1),
        )
        self.assertIsNone(store.get(self.p1))
        raw = self.db.fetch_one(
            "SELECT access_token,refresh_token FROM google_oauth_tokens WHERE user_id='u2'"
        )
        self.assertNotIn("u2-access-secret", raw["access_token"])
        self.assertNotIn("u2-refresh-secret", raw["refresh_token"])

    def test_mobile_snapshot_never_contains_other_tenant_objects(self):
        snapshot = self.app1.full_resync()
        encoded = repr(snapshot)
        self.assertIn("One task", encoded)
        self.assertNotIn("Two task", encoded)
        self.assertNotIn("two@example.com", encoded)


if __name__ == "__main__":
    unittest.main()
