from __future__ import annotations

from datetime import date
from pathlib import Path
import asyncio
import tempfile
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from httpx import ASGITransport, AsyncClient

from fastapi_app import create_app
from identity import AuthenticatedPrincipal
from unit_of_work import DatabaseFactory


class _Verifier:
    def verify(self, token: str) -> AuthenticatedPrincipal:
        user_id = {"token-u1": "u1", "token-u2": "u2"}.get(token)
        if user_id is None:
            raise AssertionError("Unexpected test token")
        return AuthenticatedPrincipal(
            user_id=user_id,
            email=f"{user_id}@example.test",
            display_name=user_id,
            claims={"sub": user_id},
        )


class FastAPITaskRouteTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        path = Path(self.tempdir.name) / "api-tasks.db"
        self.factory = DatabaseFactory(f"sqlite:///{path.as_posix()}")
        db = self.factory()
        try:
            for user_id in ("u1", "u2"):
                db.create_user(user_id, f"{user_id}@example.test", user_id, "UTC")
            self.plan_u1 = db.create_plan("u1", date(2099, 1, 1), "u1 plan")
            self.plan_u2 = db.create_plan("u2", date(2099, 1, 1), "u2 plan")
            self.task_u1 = db.add_task(
                self.plan_u1, "u1", "U1 task", estimated_minutes=30
            )
            self.task_u2 = db.add_task(
                self.plan_u2, "u2", "U2 task", estimated_minutes=45
            )
        finally:
            db.close()
        self.app = create_app(
            readiness_check=lambda: {"status": "healthy", "checks": {}},
            token_verifier=_Verifier(),
            database_factory=self.factory,
        )

    def tearDown(self) -> None:
        self.factory.close()
        self.tempdir.cleanup()

    @staticmethod
    def headers(token: str, key: str | None = None) -> dict[str, str]:
        values = {"Authorization": f"Bearer {token}"}
        if key is not None:
            values["Idempotency-Key"] = key
        return values

    def task(self, task_id: int, user_id: str):
        db = self.factory()
        try:
            return db.get_task(task_id, user_id)
        finally:
            db.close()

    def test_listing_is_paginated_and_principal_scoped(self) -> None:
        with TestClient(self.app) as client:
            u1 = client.get("/v1/tasks?limit=1", headers=self.headers("token-u1"))
            u2 = client.get("/v1/tasks", headers=self.headers("token-u2"))

        self.assertEqual(200, u1.status_code)
        self.assertEqual([self.task_u1], [item["task_id"] for item in u1.json()["items"]])
        self.assertIsNone(u1.json()["next_offset"])
        self.assertEqual([self.task_u2], [item["task_id"] for item in u2.json()["items"]])

    def test_update_is_versioned_idempotent_and_retry_safe(self) -> None:
        payload = {"expected_version": 0, "title": "Updated"}
        headers = self.headers("token-u1", "update-key")
        with TestClient(self.app) as client:
            first = client.patch(f"/v1/tasks/{self.task_u1}", json=payload, headers=headers)
            replay = client.patch(f"/v1/tasks/{self.task_u1}", json=payload, headers=headers)
            mismatch = client.patch(
                f"/v1/tasks/{self.task_u1}",
                json={"expected_version": 0, "title": "Different"},
                headers=headers,
            )
            stale = client.patch(
                f"/v1/tasks/{self.task_u1}",
                json={"expected_version": 0, "description": "stale"},
                headers=self.headers("token-u1", "stale-key"),
            )

        self.assertEqual(200, first.status_code)
        self.assertEqual(1, first.json()["version"])
        self.assertFalse(first.json()["idempotent_replay"])
        self.assertEqual(200, replay.status_code)
        self.assertTrue(replay.json()["idempotent_replay"])
        self.assertEqual(409, mismatch.status_code)
        self.assertEqual("IDEMPOTENCY_CONFLICT", mismatch.json()["code"])
        self.assertEqual(409, stale.status_code)
        self.assertEqual("VERSION_CONFLICT", stale.json()["code"])
        self.assertEqual("Updated", self.task(self.task_u1, "u1")["title"])

    def test_cross_tenant_and_invalid_requests_are_safe(self) -> None:
        with TestClient(self.app) as client:
            foreign = client.patch(
                f"/v1/tasks/{self.task_u2}",
                json={"expected_version": 0, "title": "steal"},
                headers=self.headers("token-u1", "foreign-key"),
            )
            invalid = client.patch(
                f"/v1/tasks/{self.task_u1}",
                json={"expected_version": 0, "title": "   ", "unknown": 1},
                headers=self.headers("token-u1", "invalid-key"),
            )

        self.assertEqual(404, foreign.status_code)
        self.assertEqual("RESOURCE_NOT_FOUND", foreign.json()["code"])
        self.assertEqual(422, invalid.status_code)
        self.assertEqual("VALIDATION_ERROR", invalid.json()["code"])
        self.assertNotIn("unknown", invalid.text)

    def test_notification_preferences_are_private_scoped_and_idempotent(self) -> None:
        with TestClient(self.app) as client:
            initial_u1 = client.get(
                "/v1/notifications/preferences", headers=self.headers("token-u1")
            )
            initial_u2 = client.get(
                "/v1/notifications/preferences", headers=self.headers("token-u2")
            )
            headers = self.headers("token-u1", "notification-key")
            updated = client.patch(
                "/v1/notifications/preferences",
                json={"daily_summary_enabled": True, "quiet_hours_start": "22:15:00", "quiet_hours_end": "07:30:00"},
                headers=headers,
            )
            replay = client.patch(
                "/v1/notifications/preferences",
                json={"daily_summary_enabled": True, "quiet_hours_start": "22:15:00", "quiet_hours_end": "07:30:00"},
                headers=headers,
            )
            unchanged_u2 = client.get(
                "/v1/notifications/preferences", headers=self.headers("token-u2")
            )

        self.assertEqual("u1", initial_u1.json()["user_id"])
        self.assertEqual("u2", initial_u2.json()["user_id"])
        self.assertEqual("no-store", initial_u1.headers["cache-control"])
        self.assertEqual("Authorization", initial_u1.headers["vary"])
        self.assertEqual(200, updated.status_code)
        self.assertTrue(updated.json()["daily_summary_enabled"])
        self.assertFalse(updated.json()["idempotent_replay"])
        self.assertTrue(replay.json()["idempotent_replay"])
        self.assertEqual("no-store", updated.headers["cache-control"])
        self.assertEqual(
            initial_u2.json()["daily_summary_enabled"],
            unchanged_u2.json()["daily_summary_enabled"],
        )

    def test_notification_preferences_reject_invalid_or_unauthenticated_requests(self) -> None:
        with TestClient(self.app) as client:
            unauthenticated = client.get("/v1/notifications/preferences")
            invalid = client.patch(
                "/v1/notifications/preferences",
                json={"default_reminder_lead_minutes": 999},
                headers=self.headers("token-u1", "bad-notification-key"),
            )

        self.assertEqual(401, unauthenticated.status_code)
        self.assertEqual(422, invalid.status_code)
        self.assertEqual("VALIDATION_ERROR", invalid.json()["code"])

    def test_notification_device_registration_is_private_and_token_safe(self) -> None:
        payload = {
            "device_id": "android-installation-1",
            "push_token": "private-fcm-token",
            "app_identifier": "com.zenmaestro.app",
            "app_version": "0.1.0",
            "locale": "en",
            "timezone": "Africa/Cairo",
        }
        async def scenario():
            async with AsyncClient(
                transport=ASGITransport(app=self.app), base_url="http://test",
            ) as client:
                registered = await client.post(
                    "/v1/notifications/devices",
                    json=payload,
                    headers=self.headers("token-u1", "device-register-1"),
                )
                replay = await client.post(
                    "/v1/notifications/devices",
                    json=payload,
                    headers=self.headers("token-u1", "device-register-1"),
                )
                invalidated = await client.delete(
                    "/v1/notifications/devices/android-installation-1",
                    headers=self.headers("token-u1", "device-invalidate-1"),
                )
                return registered, replay, invalidated

        registered, replay, invalidated = asyncio.run(scenario())

        self.assertEqual(200, registered.status_code)
        self.assertEqual("android-installation-1", registered.json()["device_id"])
        self.assertEqual("fcm", registered.json()["provider"])
        self.assertNotIn("push_token", registered.text)
        self.assertNotIn("private-fcm-token", registered.text)
        self.assertFalse(registered.json()["idempotent_replay"])
        self.assertTrue(replay.json()["idempotent_replay"])
        self.assertEqual(200, invalidated.status_code)
        self.assertTrue(invalidated.json()["invalidated"])

    def test_mobile_task_create_lifecycle_and_delete_routes(self) -> None:
        with TestClient(self.app) as client:
            created = client.post(
                "/v1/tasks",
                json={"plan_id": self.plan_u1, "title": "Mobile task", "estimated_minutes": 20},
                headers=self.headers("token-u1", "mobile-create"),
            )
            task_id = created.json()["task_id"]
            started = client.post(
                f"/v1/tasks/{task_id}/start",
                json={"expected_version": created.json()["version"]},
                headers=self.headers("token-u1", "mobile-start"),
            )
            completed = client.post(
                f"/v1/tasks/{task_id}/complete",
                json={"expected_version": started.json()["version"]},
                headers=self.headers("token-u1", "mobile-complete"),
            )
            delete_terminal = client.delete(
                f"/v1/tasks/{task_id}?expected_version={completed.json()['version']}",
                headers=self.headers("token-u1", "mobile-delete"),
            )

        self.assertEqual(200, created.status_code)
        self.assertEqual("in_progress", started.json()["status"])
        self.assertEqual("completed", completed.json()["status"])
        self.assertEqual(200, delete_terminal.status_code)
        self.assertTrue(delete_terminal.json()["deleted"])

    def test_coach_routes_fail_safely_without_provider_configuration(self) -> None:
        with patch.dict("os.environ", {"GOOGLE_API_KEY": "", "GEMINI_API_KEY": ""}, clear=False):
            with TestClient(self.app) as client:
                chat = client.post(
                    "/v1/coach/chat",
                    json={"message": "Help me plan"},
                    headers=self.headers("token-u1"),
                )
        self.assertEqual(503, chat.status_code)
        self.assertEqual("CONFIGURATION_ERROR", chat.json()["code"])

    def test_failure_after_mutation_rolls_back_route_transaction(self) -> None:
        with patch(
            "mobile_repository.MobileRepository.record_change",
            side_effect=RuntimeError("after mutation"),
        ):
            with TestClient(self.app, raise_server_exceptions=False) as client:
                response = client.patch(
                    f"/v1/tasks/{self.task_u1}",
                    json={"expected_version": 0, "title": "Must roll back"},
                    headers=self.headers("token-u1", "rollback-key"),
                )

        self.assertEqual(500, response.status_code)
        self.assertEqual("INTERNAL_ERROR", response.json()["code"])
        task = self.task(self.task_u1, "u1")
        self.assertEqual("U1 task", task["title"])
        self.assertEqual(0, task["version"])
        self.assertEqual(0, self.factory.pool_metrics()["checked_out"])

    def test_openapi_matches_the_implemented_slice(self) -> None:
        with TestClient(self.app) as client:
            spec = client.get("/openapi.json").json()

        self.assertEqual(
            {
                "/v1/tasks", "/v1/tasks/{task_id}", "/v1/tasks/{task_id}/{command}", "/v1/analytics",
                "/v1/home",
                "/v1/profile", "/v1/profile/display-name", "/v1/profile/timezone",
                "/v1/notifications/preferences", "/v1/notifications/devices", "/v1/notifications/devices/{device_id}",
                "/v1/calendar", "/v1/calendar/oauth/start", "/v1/calendar/oauth/complete",
                "/v1/calendar/calendars", "/v1/calendar/sync", "/v1/calendar/disconnect",
                "/v1/coach/recommendations/today", "/v1/coach/chat",
                "/v1/coach/voice/transcribe",
                "/v1/planning/draft", "/v1/planning/apply",
                "/v1/plans/today", "/v1/plans/by-date/{plan_date}",
                "/v1/scheduling/apply", "/v1/scheduling/tasks/{task_id}/move",
                "/v1/scheduling/operations/{operation_id}/undo",
                "/v1/future-planning/candidates", "/v1/future-planning/preview",
                "/v1/future-planning/apply",
            },
            set(spec["paths"]),
        )
        operation = spec["paths"]["/v1/tasks/{task_id}"]["patch"]
        self.assertIn("Idempotency-Key", [p["name"] for p in operation["parameters"]])
        self.assertIn("422", operation["responses"])

    def test_calendar_mobile_status_is_private_and_reports_configuration_safely(self) -> None:
        with patch.dict(
            "os.environ",
            {"GOOGLE_CLIENT_ID": "", "GOOGLE_CLIENT_SECRET": "", "COACHAI_TOKEN_ENCRYPTION_KEY": ""},
            clear=False,
        ):
            with TestClient(self.app) as client:
                unauthenticated = client.get("/v1/calendar")
                u1 = client.get("/v1/calendar", headers=self.headers("token-u1"))
                start = client.post("/v1/calendar/oauth/start", headers=self.headers("token-u1"))
                callback = client.get(
                    "/v1/calendar/oauth/callback?code=safe-code&state=safe-state",
                    follow_redirects=False,
                )

        self.assertEqual(401, unauthenticated.status_code)
        self.assertEqual(200, u1.status_code)
        self.assertEqual({"provider": "google", "connected": False, "selected_calendars": []}, u1.json())
        self.assertEqual("no-store", u1.headers["cache-control"])
        self.assertEqual(503, start.status_code)
        self.assertEqual("CONFIGURATION_ERROR", start.json()["code"])
        self.assertEqual(302, callback.status_code)
        self.assertEqual(
            "coachai://calendar/oauth?code=safe-code&state=safe-state",
            callback.headers["location"],
        )
        self.assertEqual("no-store", callback.headers["cache-control"])

    def test_database_integer_and_pagination_bounds_fail_before_access(self) -> None:
        enormous = 10**100
        with TestClient(self.app) as client:
            offset = client.get(
                f"/v1/tasks?offset={enormous}", headers=self.headers("token-u1")
            )
            task_id = client.patch(
                f"/v1/tasks/{enormous}",
                json={"expected_version": 0, "title": "bounded"},
                headers=self.headers("token-u1", "bounded-id"),
            )
            version = client.patch(
                f"/v1/tasks/{self.task_u1}",
                json={"expected_version": enormous, "title": "bounded"},
                headers=self.headers("token-u1", "bounded-version"),
            )

        for response in (offset, task_id, version):
            self.assertEqual(422, response.status_code)
            self.assertEqual("VALIDATION_ERROR", response.json()["code"])
            self.assertNotIn(str(enormous), response.text)
        self.assertEqual("U1 task", self.task(self.task_u1, "u1")["title"])


if __name__ == "__main__":
    unittest.main()
