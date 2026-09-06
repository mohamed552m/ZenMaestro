from typing import Annotated

from fastapi import Depends, Header, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from firebase_admin import app_check as firebase_app_check
from firebase_admin import auth as firebase_auth
from pydantic import BaseModel

from app.core.firebase import (
    FirebaseConfigurationError,
    verify_firebase_app_check_token,
    verify_firebase_id_token,
)
from app.core.config import get_settings


bearer_scheme = HTTPBearer(auto_error=False)


class AuthenticatedUser(BaseModel):
    uid: str
    email: str | None = None
    name: str | None = None
    email_verified: bool = False


def _unauthorized() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="A valid Firebase ID token is required.",
        headers={"WWW-Authenticate": "Bearer"},
    )


def get_current_user(
    credentials: Annotated[
        HTTPAuthorizationCredentials | None,
        Depends(bearer_scheme),
    ] = None,
    app_check_token: Annotated[
        str | None,
        Header(alias="X-Firebase-AppCheck"),
    ] = None,
) -> AuthenticatedUser:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise _unauthorized()

    try:
        claims = verify_firebase_id_token(credentials.credentials)
    except FirebaseConfigurationError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Firebase authentication is not configured on the server.",
        ) from exc
    except (
        firebase_auth.InvalidIdTokenError,
        firebase_auth.ExpiredIdTokenError,
        firebase_auth.RevokedIdTokenError,
        firebase_auth.UserDisabledError,
        ValueError,
    ) as exc:
        raise _unauthorized() from exc

    uid = claims.get("uid") or claims.get("sub")
    if not isinstance(uid, str) or not uid:
        raise _unauthorized()

    if get_settings().require_app_check:
        if not app_check_token:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="A valid Firebase App Check token is required.",
            )
        try:
            verify_firebase_app_check_token(app_check_token)
        except FirebaseConfigurationError as exc:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Firebase App Check is not configured on the server.",
            ) from exc
        except (firebase_app_check.InvalidTokenError, ValueError) as exc:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="A valid Firebase App Check token is required.",
            ) from exc

    return AuthenticatedUser(
        uid=uid,
        email=claims.get("email") if isinstance(claims.get("email"), str) else None,
        name=claims.get("name") if isinstance(claims.get("name"), str) else None,
        email_verified=claims.get("email_verified") is True,
    )
