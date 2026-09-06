from typing import Annotated

from fastapi import APIRouter, Depends, Response, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.dependencies.auth import AuthenticatedUser, get_current_user
from app.db.session import get_db_session
from app.models.chat import ChatConversation
from app.models.plan import Plan
from app.schemas.account import AccountPreferencesUpdate, AccountProfileResponse
from app.services.accounts import ensure_database_user


router = APIRouter(prefix="/account", tags=["account"])
DatabaseSession = Annotated[Session, Depends(get_db_session)]
CurrentUser = Annotated[AuthenticatedUser, Depends(get_current_user)]


@router.get("/me", response_model=AuthenticatedUser)
def read_current_user(
    user: Annotated[AuthenticatedUser, Depends(get_current_user)],
) -> AuthenticatedUser:
    return user


def _profile_response(user, identity: AuthenticatedUser) -> AccountProfileResponse:
    return AccountProfileResponse(
        uid=user.uid,
        email=user.email,
        display_name=user.display_name,
        email_verified=identity.email_verified,
        timezone=user.timezone,
        learning_day_start=user.learning_day_start,
        reminders_enabled=user.reminders_enabled,
    )


@router.get("/profile", response_model=AccountProfileResponse)
def read_profile(session: DatabaseSession, identity: CurrentUser) -> AccountProfileResponse:
    user = ensure_database_user(session, identity)
    session.commit()
    session.refresh(user)
    return _profile_response(user, identity)


@router.patch("/preferences", response_model=AccountProfileResponse)
def update_preferences(
    payload: AccountPreferencesUpdate,
    session: DatabaseSession,
    identity: CurrentUser,
) -> AccountProfileResponse:
    user = ensure_database_user(session, identity)
    updates = payload.model_dump(exclude_unset=True, exclude_none=True)
    for field, value in updates.items():
        setattr(user, field, value)
    session.commit()
    session.refresh(user)
    return _profile_response(user, identity)


@router.delete("/learning-data", status_code=status.HTTP_204_NO_CONTENT)
def delete_learning_data(
    session: DatabaseSession,
    identity: CurrentUser,
) -> Response:
    plans = session.scalars(select(Plan).where(Plan.user_uid == identity.uid)).all()
    for plan in plans:
        session.delete(plan)
    conversations = session.scalars(
        select(ChatConversation).where(ChatConversation.user_uid == identity.uid)
    ).all()
    for conversation in conversations:
        session.delete(conversation)
    session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)
