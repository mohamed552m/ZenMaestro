from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
import os
import sys
import tempfile
from types import ModuleType
import unittest
from unittest.mock import patch
from sqlalchemy.exc import IntegrityError

from backend_errors import AuthenticationRequired, ConfigurationError, ExternalProviderFailure
from database import Database
from identity import (
    AuthenticatedPrincipal,
    FirebaseTokenVerifier,
    _service_account_from_env,
    get_or_create_user_from_principal,
)


class FirebaseAuthenticationTests(unittest.TestCase):
    project_id = "coachai-test"

    def claims(self, **changes):
        values = {
            "sub": "firebase-u1",
            "email": "u1@example.com",
            "name": "User One",
            "aud": self.project_id,
            "iss": f"https://securetoken.google.com/{self.project_id}",
            "exp": int(datetime.now(timezone.utc).timestamp()) + 3600,
        }
        values.update(changes)
        return values

    def verifier(self, claims=None, *, raises=None):
        def verify(_token, **_kwargs):
            if raises is not None:
                raise raises
            return self.claims() if claims is None else claims

        return FirebaseTokenVerifier(
            project_id=self.project_id,
            verify_callable=verify,
        )

    def test_valid_token_returns_immutable_trusted_principal(self):
        principal = self.verifier().verify("signed-token")
        self.assertEqual("firebase-u1", principal.user_id)
        self.assertEqual("u1@example.com", principal.email)
        with self.assertRaises(TypeError):
            principal.claims["sub"] = "spoofed"

    def test_missing_or_provider_rejected_token_is_rejected(self):
        with self.assertRaises(AuthenticationRequired):
            self.verifier().verify("")
        with self.assertRaises(AuthenticationRequired):
            self.verifier(raises=ValueError("provider detail")).verify("bad")

    def test_certificate_fetch_failure_is_retried_then_reported_as_provider_failure(self):
        from firebase_admin._token_gen import CertificateFetchError

        verify = unittest.mock.Mock(
            side_effect=CertificateFetchError("unavailable", RuntimeError())
        )
        verifier = FirebaseTokenVerifier(
            project_id=self.project_id,
            verify_callable=verify,
        )
        with patch("identity.time.sleep"), self.assertRaises(ExternalProviderFailure):
            verifier.verify("signed-token")
        self.assertEqual(2, verify.call_count)

    def test_certificate_fetch_retry_can_recover(self):
        from firebase_admin._token_gen import CertificateFetchError

        verify = unittest.mock.Mock(
            side_effect=[
                CertificateFetchError("unavailable", RuntimeError()),
                self.claims(),
            ]
        )
        verifier = FirebaseTokenVerifier(
            project_id=self.project_id,
            verify_callable=verify,
        )
        with patch("identity.time.sleep"):
            principal = verifier.verify("signed-token")
        self.assertEqual("firebase-u1", principal.user_id)
        self.assertEqual(2, verify.call_count)

    def test_service_account_environment_must_match_project(self):
        value = '{"type":"service_account","project_id":"wrong-project"}'
        with patch.dict(os.environ, {"FIREBASE_SERVICE_ACCOUNT_JSON": value}):
            with self.assertRaises(ConfigurationError):
                _service_account_from_env(self.project_id)

    def test_expired_wrong_audience_wrong_issuer_and_disabled_are_rejected(self):
        invalid = (
            self.claims(exp=1),
            self.claims(aud="another-project"),
            self.claims(iss="https://attacker.invalid"),
            self.claims(disabled=True),
        )
        for claims in invalid:
            with self.subTest(claims=claims):
                with self.assertRaises(AuthenticationRequired):
                    self.verifier(claims).verify("token")

    def test_default_adapter_initializes_official_admin_app(self):
        firebase_admin = ModuleType("firebase_admin")
        auth = ModuleType("firebase_admin.auth")
        firebase_app = object()
        calls = []

        def get_app():
            raise ValueError("no default app")

        def initialize_app(*, options):
            calls.append(("initialize", options))
            return firebase_app

        def verify_id_token(token, *, app, check_revoked, clock_skew_seconds):
            calls.append(("verify", token, app, check_revoked, clock_skew_seconds))
            return self.claims()

        firebase_admin.get_app = get_app
        firebase_admin.initialize_app = initialize_app
        firebase_admin.auth = auth
        auth.verify_id_token = verify_id_token
        with patch.dict(
            sys.modules,
            {"firebase_admin": firebase_admin, "firebase_admin.auth": auth},
        ):
            principal = FirebaseTokenVerifier(project_id=self.project_id).verify(
                "signed-token"
            )

        self.assertEqual("firebase-u1", principal.user_id)
        self.assertEqual(("initialize", {"projectId": self.project_id}), calls[0])
        self.assertEqual(("verify", "signed-token", firebase_app, True, 5), calls[1])


class PrincipalProvisioningTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        path = Path(self.tempdir.name) / "auth.db"
        self.db = Database(f"sqlite:///{path.as_posix()}")

    def tearDown(self):
        self.db.close()
        self.tempdir.cleanup()

    @staticmethod
    def principal(user_id="firebase-u1"):
        return AuthenticatedPrincipal(
            user_id=user_id,
            email=f"{user_id}@example.com",
            display_name="Firebase User",
            claims={"sub": user_id},
        )

    def test_provisions_idempotently_from_verified_principal(self):
        first = get_or_create_user_from_principal(
            self.db, self.principal(), timezone_name="Africa/Cairo"
        )
        second = get_or_create_user_from_principal(self.db, self.principal())
        self.assertEqual("firebase-u1", first["user_id"])
        self.assertEqual(first["user_id"], second["user_id"])
        self.assertEqual(1, self.db.fetch_one("SELECT COUNT(*) AS n FROM users")["n"])

    def test_concurrent_first_login_reuses_row_created_by_parallel_request(self):
        principal = self.principal()
        create_user = self.db.create_user

        def parallel_insert_then_unique_conflict(**values):
            create_user(**values)
            raise IntegrityError("duplicate user", params=None, orig=Exception("duplicate"))

        with patch.object(
            self.db,
            "create_user",
            side_effect=parallel_insert_then_unique_conflict,
        ):
            user = get_or_create_user_from_principal(self.db, principal)

        self.assertEqual(principal.user_id, user["user_id"])
        self.assertEqual(1, self.db.fetch_one("SELECT COUNT(*) AS n FROM users")["n"])
        self.assertEqual(
            1,
            self.db.fetch_one("SELECT COUNT(*) AS n FROM user_profiles")["n"],
        )

    def test_disabled_local_account_cannot_be_provisioned(self):
        get_or_create_user_from_principal(self.db, self.principal())
        self.db.execute("UPDATE users SET is_disabled=1 WHERE user_id=?", ("firebase-u1",))
        self.db.commit()
        with self.assertRaises(AuthenticationRequired):
            get_or_create_user_from_principal(self.db, self.principal())


if __name__ == "__main__":
    unittest.main()
