from __future__ import annotations

from datetime import date, timedelta
from pathlib import Path
import tempfile
import unittest

from backend_errors import VersionConflict
from database import Database
from identity import AuthenticatedPrincipal
from mobile_repository import MobileRepository
from notifications import NotificationRepository
from task_application import TaskApplicationService


class MobileSyncTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        path = Path(self.tempdir.name) / "sync.db"
        self.db = Database(f"sqlite:///{path.as_posix()}")
        self.db.create_user(
            "u1", "u1@example.com", "User", "Africa/Cairo", password_hash="secret-hash"
        )
        self.plan = self.db.create_plan(
            "u1", date.today() + timedelta(days=1), "private raw input"
        )
        principal = AuthenticatedPrincipal("u1", "u1@example.com", "User", {"sub": "u1"})
        self.tasks = TaskApplicationService(self.db, principal)
        self.repo = MobileRepository(self.db)

    def tearDown(self):
        self.db.close()
        self.tempdir.cleanup()

    def test_delta_cursor_tombstone_and_stale_cursor(self):
        created = self.tasks.create_task(
            plan_id=self.plan, title="Task", estimated_minutes=15,
            idempotency_key="create",
        )
        first = self.repo.sync_since("u1", 0)
        self.assertFalse(first["full_resync_required"])
        self.assertEqual("upsert", first["changes"][0]["change_type"])

        deleted = self.tasks.delete_task(
            created["task_id"], expected_version=0, idempotency_key="delete"
        )
        second = self.repo.sync_since("u1", first["revision"])
        self.assertEqual("delete", second["changes"][0]["change_type"])
        self.assertEqual(deleted["sync_revision"], second["revision"])

        self.db.execute(
            "UPDATE application_sync_state SET minimum_available_revision=? WHERE user_id=?",
            (second["revision"], "u1"),
        )
        self.db.commit()
        stale = self.repo.sync_since("u1", 0)
        self.assertTrue(stale["full_resync_required"])

    def test_snapshot_is_secret_free_and_ahead_cursor_conflicts(self):
        self.tasks.create_task(
            plan_id=self.plan, title="Task", estimated_minutes=15,
            idempotency_key="create",
        )
        notifications = NotificationRepository(self.db)
        notifications.set_type_preference(
            "u1", "task_reminder", preferred_channel="local", lead_minutes=15,
        )
        self.db.save_selected_calendars("u1", [{
            "calendar_id": "primary", "calendar_name": "My Calendar",
            "color": "#123456", "is_primary": True,
        }])
        self.db.save_google_tokens(
            "u1", "secret-access-token", "secret-refresh-token",
            "2099-01-01 00:00:00", "calendar.readonly",
        )
        snapshot = self.repo.full_snapshot("u1")
        self.assertNotIn("password_hash", snapshot["user"])
        serialized = str(snapshot)
        self.assertNotIn("secret-hash", serialized)
        self.assertNotIn("access_token", serialized)
        self.assertNotIn("secret-access-token", serialized)
        self.assertNotIn("secret-refresh-token", serialized)
        self.assertEqual(
            "task_reminder",
            snapshot["notification_type_preferences"][0]["notification_type"],
        )
        self.assertEqual("primary", snapshot["selected_calendars"][0]["calendar_id"])
        self.assertTrue(snapshot["calendar_connection"]["connected"])
        self.assertEqual(
            "calendar.readonly", snapshot["calendar_connection"]["scopes"]
        )
        with self.assertRaises(VersionConflict):
            self.repo.sync_since("u1", snapshot["revision"] + 1)


if __name__ == "__main__":
    unittest.main()
