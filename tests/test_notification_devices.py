from database import OwnershipError
from notifications import DomainEvent, NotificationEventType
from notifications.repository import NotificationRepository
from unittest.mock import Mock

from tests.notification_test_support import NotificationTestCase


class NotificationDeviceTests(NotificationTestCase):
    def test_postgresql_registration_locks_device_and_token_in_stable_order(self):
        db = Mock()
        db.is_postgresql = True
        repository = NotificationRepository(db)

        repository._lock_device_registration(
            device_id="phone",
            provider="fcm",
            push_token="token-1",
        )

        self.assertEqual(2, db.fetch_one.call_count)
        identities = [call.args[1][0] for call in db.fetch_one.call_args_list]
        self.assertEqual(sorted(identities), identities)
        self.assertEqual(
            {
                "coachai:notification:device:phone",
                "coachai:notification:token:fcm:token-1",
            },
            set(identities),
        )

    def test_multiple_devices_and_single_primary(self):
        first = self.repo.register_device(
            "u1", device_id="phone", platform="android", provider="fcm",
            push_token="token-1", locale="ar", timezone_name="Africa/Cairo",
            is_primary=True,
        )
        second = self.repo.register_device(
            "u1", device_id="tablet", platform="ios", provider="apns",
            push_token="token-2", is_primary=True,
        )
        devices = self.repo.list_devices("u1")
        self.assertEqual(2, len(devices))
        self.assertEqual(1, sum(int(device["is_primary"]) for device in devices))
        self.assertEqual("tablet", [d for d in devices if d["is_primary"]][0]["device_id"])
        self.assertEqual("ar", first["locale"])
        self.assertEqual("ios", second["platform"])

    def test_token_invalidation_disables_only_owned_device(self):
        self.repo.register_device(
            "u1", device_id="phone", platform="android", provider="fcm",
            push_token="rotating-token",
        )
        self.repo.invalidate_device_token("u1", "phone")
        row = self.repo.get_device("u1", "phone")
        self.assertIsNone(row["push_token"])
        self.assertEqual(0, row["is_enabled"])
        self.assertIsNotNone(row["token_invalidated_at"])
        with self.assertRaises(OwnershipError):
            self.repo.invalidate_device_token("u2", "phone")

    def test_acknowledged_revision_cannot_exceed_user_sync(self):
        self.repo.register_device(
            "u1", device_id="phone", platform="android", provider="none",
        )
        self.repo.enqueue_event(DomainEvent.create(
            user_id="u1", event_type=NotificationEventType.SCHEDULE_OPERATION_APPLIED,
            idempotency_key="sync-one",
        ), bump_sync_revision=True)
        self.db.connection.commit()
        self.repo.acknowledge_schedule_revision("u1", "phone", 1)
        self.assertEqual(1, self.repo.get_device("u1", "phone")["last_acknowledged_schedule_revision"])
        with self.assertRaises(ValueError):
            self.repo.acknowledge_schedule_revision("u1", "phone", 2)

    def test_invalid_platform_provider_and_locale_are_rejected(self):
        with self.assertRaises(ValueError):
            self.repo.register_device(
                "u1", device_id="bad", platform="web", provider="fcm",
            )
        with self.assertRaises(ValueError):
            self.repo.register_device(
                "u1", device_id="bad", platform="ios", provider="webpush",
            )
        with self.assertRaises(ValueError):
            self.repo.register_device(
                "u1", device_id="bad", platform="ios", provider="apns", locale="fr",
            )
        with self.assertRaises(ValueError):
            self.repo.register_device(
                "u1", device_id="bad", platform="android", provider="none",
                timezone_name="Not/A_Real_Zone",
            )


if __name__ == "__main__":
    import unittest
    unittest.main()
