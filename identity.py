"""Trusted authentication principals and Firebase token verification.

This module has no Streamlit or HTTP dependency.  A later transport verifies a
credential once, then passes the immutable principal to application services.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import logging
from types import MappingProxyType
from typing import Any, Callable, Mapping, Protocol

from backend_errors import AuthenticationRequired, ConfigurationError


logger = logging.getLogger("coachai.auth")


@dataclass(frozen=True, slots=True)
class AuthenticatedPrincipal:
    user_id: str
    email: str
    display_name: str
    claims: Mapping[str, Any]

    def __post_init__(self) -> None:
        if not self.user_id.strip():
            raise AuthenticationRequired("The verified identity has no subject.")
        object.__setattr__(self, "claims", MappingProxyType(dict(self.claims)))


class TokenVerifier(Protocol):
    def verify(self, token: str) -> AuthenticatedPrincipal: ...


class FirebaseTokenVerifier:
    """Firebase Admin adapter with injectable verification for deterministic tests."""

    def __init__(
        self,
        *,
        project_id: str,
        verify_callable: Callable[..., Mapping[str, Any]] | None = None,
        check_revoked: bool = True,
        clock_skew_seconds: int = 5,
    ) -> None:
        if not project_id.strip():
            raise ConfigurationError("FIREBASE_PROJECT_ID is required.")
        self.project_id = project_id.strip()
        self._verify_callable = verify_callable
        self.check_revoked = check_revoked
        if not 0 <= clock_skew_seconds <= 60:
            raise ConfigurationError("Firebase clock skew must be between 0 and 60 seconds.")
        self.clock_skew_seconds = clock_skew_seconds

    def _verifier(self) -> Callable[..., Mapping[str, Any]]:
        if self._verify_callable is not None:
            return self._verify_callable
        try:
            import firebase_admin
            from firebase_admin import auth
        except ImportError as exc:
            raise ConfigurationError(
                "firebase-admin is required for real Firebase token verification."
            ) from exc
        try:
            firebase_app = firebase_admin.get_app()
        except ValueError:
            try:
                if self.check_revoked:
                    firebase_app = firebase_admin.initialize_app(
                        options={"projectId": self.project_id}
                    )
                else:
                    # Local signature-only verification deliberately avoids
                    # Application Default Credentials. Public Firebase signing
                    # certificates still verify the JWT below; production uses
                    # the default check_revoked=True path with Admin credentials.
                    from firebase_admin import credentials
                    from google.auth.credentials import AnonymousCredentials

                    class SignatureOnlyCredential(credentials.Base):
                        def get_credential(self):
                            return AnonymousCredentials()

                    firebase_app = firebase_admin.initialize_app(
                        SignatureOnlyCredential(),
                        options={"projectId": self.project_id},
                    )
            except ValueError:
                # Another request may have initialized the default app between
                # get_app() and initialize_app(). Reuse it without exposing the
                # provider exception or credential details.
                firebase_app = firebase_admin.get_app()

        def verify_id_token(token: str, **kwargs: Any) -> Mapping[str, Any]:
            return auth.verify_id_token(token, app=firebase_app, **kwargs)

        return verify_id_token

    def verify(self, token: str) -> AuthenticatedPrincipal:
        if not token or not token.strip():
            raise AuthenticationRequired("A Firebase ID token is required.")
        try:
            claims = dict(
                self._verifier()(
                    token.strip(),
                    check_revoked=self.check_revoked,
                    clock_skew_seconds=self.clock_skew_seconds,
                )
            )
        except Exception as exc:
            # Provider exception details are deliberately not exposed.
            logger.warning(
                "Firebase token verification failed (%s).",
                type(exc).__name__,
            )
            raise AuthenticationRequired("The Firebase ID token is invalid.") from exc

        subject = str(claims.get("uid") or claims.get("sub") or "").strip()
        audience = claims.get("aud")
        issuer = str(claims.get("iss") or "")
        expected_issuer = f"https://securetoken.google.com/{self.project_id}"
        now = int(datetime.now(timezone.utc).timestamp())
        try:
            expires_at = int(claims.get("exp", 0))
        except (TypeError, ValueError):
            expires_at = 0
        if audience != self.project_id or issuer != expected_issuer or expires_at <= now:
            raise AuthenticationRequired("The Firebase ID token is invalid or expired.")
        if not subject or claims.get("disabled") is True:
            raise AuthenticationRequired("The Firebase user is disabled or invalid.")
        return AuthenticatedPrincipal(
            user_id=subject,
            email=str(claims.get("email") or f"{subject}@firebase.local"),
            display_name=str(claims.get("name") or claims.get("email") or "CoachAI User"),
            claims=claims,
        )


def get_or_create_user_from_principal(
    db: Any,
    principal: AuthenticatedPrincipal,
    *,
    timezone_name: str = "UTC",
) -> dict:
    """Provision the local CoachAI row from an already verified identity."""
    row = db.get_or_create_user(
        user_id=principal.user_id,
        email=principal.email,
        display_name=principal.display_name,
        timezone=timezone_name,
    )
    if row is None:
        raise AuthenticationRequired("The authenticated user could not be provisioned.")
    if bool(row.get("is_disabled") or 0):
        raise AuthenticationRequired("The authenticated user is disabled.")
    return row
