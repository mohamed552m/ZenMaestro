from collections.abc import Mapping
from typing import Any

from firebase_admin import App, app_check, auth, credentials, get_app, initialize_app
from google.auth.exceptions import DefaultCredentialsError

from app.core.config import get_settings


class FirebaseConfigurationError(RuntimeError):
    """Raised when server-side Firebase credentials are unavailable or invalid."""


def get_firebase_app() -> App:
    try:
        return get_app()
    except ValueError:
        pass

    settings = get_settings()
    options = (
        {"projectId": settings.firebase_project_id}
        if settings.firebase_project_id
        else None
    )

    try:
        credential = (
            credentials.Certificate(str(settings.firebase_credentials_path))
            if settings.firebase_credentials_path
            else credentials.ApplicationDefault()
        )
        return initialize_app(credential, options=options)
    except (DefaultCredentialsError, FileNotFoundError, ValueError) as exc:
        raise FirebaseConfigurationError(
            "Firebase Admin credentials are not configured for this environment."
        ) from exc


def verify_firebase_id_token(token: str) -> Mapping[str, Any]:
    try:
        return auth.verify_id_token(
            token,
            app=get_firebase_app(),
            check_revoked=True,
        )
    except DefaultCredentialsError as exc:
        raise FirebaseConfigurationError(
            "Firebase Admin credentials are not configured for this environment."
        ) from exc


def verify_firebase_app_check_token(token: str) -> Mapping[str, Any]:
    try:
        return app_check.verify_token(token, app=get_firebase_app())
    except DefaultCredentialsError as exc:
        raise FirebaseConfigurationError(
            "Firebase Admin credentials are not configured for this environment."
        ) from exc
