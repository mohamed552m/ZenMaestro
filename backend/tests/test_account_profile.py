import asyncio
from collections.abc import Generator

from httpx import ASGITransport, AsyncClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from app import models  # noqa: F401
from app.api.dependencies.auth import AuthenticatedUser, get_current_user
from app.db.base import Base
from app.db.session import get_db_session
from app.main import app
from app.models.chat import ChatConversation, ChatMessage


engine = create_engine(
    "sqlite+pysqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)


def override_session() -> Generator[Session, None, None]:
    with SessionFactory() as session:
        yield session


def request(method: str, path: str, json: dict | None = None, uid: str = "profile-user"):
    async def send_request():
        app.dependency_overrides[get_db_session] = override_session
        app.dependency_overrides[get_current_user] = lambda: AuthenticatedUser(
            uid=uid,
            email=f"{uid}@example.com",
            name="Zen Learner",
            email_verified=True,
        )
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            return await client.request(method, path, json=json)

    try:
        return asyncio.run(send_request())
    finally:
        app.dependency_overrides.clear()


def setup_function() -> None:
    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)


def test_profile_is_created_from_verified_identity() -> None:
    response = request("GET", "/api/v1/account/profile")

    assert response.status_code == 200
    assert response.json() == {
        "uid": "profile-user",
        "email": "profile-user@example.com",
        "display_name": "Zen Learner",
        "email_verified": True,
        "timezone": "UTC",
        "learning_day_start": "09:00:00",
        "reminders_enabled": True,
    }


def test_preferences_can_be_updated_and_timezone_is_validated() -> None:
    updated = request(
        "PATCH",
        "/api/v1/account/preferences",
        {
            "timezone": "Africa/Cairo",
            "learning_day_start": "08:30:00",
            "reminders_enabled": False,
        },
    )

    assert updated.status_code == 200
    assert updated.json()["timezone"] == "Africa/Cairo"
    assert updated.json()["learning_day_start"] == "08:30:00"
    assert updated.json()["reminders_enabled"] is False
    assert request(
        "PATCH",
        "/api/v1/account/preferences",
        {"timezone": "Not/A-Timezone"},
    ).status_code == 422


def test_delete_learning_data_does_not_delete_profile() -> None:
    created = request(
        "POST",
        "/api/v1/plans/2026-09-05/tasks",
        {"title": "Private learning task"},
    )
    assert created.status_code == 201
    request("GET", "/api/v1/account/profile")
    with SessionFactory() as session:
        conversation = ChatConversation(
            id="2402d4bb-e2b0-43ed-91ef-0475c49ce80f",
            user_uid="profile-user",
            title="Private chat",
        )
        conversation.messages.append(
            ChatMessage(
                id="2dd45fc1-3b07-4678-a029-6013a11bb05c",
                role="user",
                content="Private message",
            )
        )
        session.add(conversation)
        session.commit()

    deleted = request("DELETE", "/api/v1/account/learning-data")

    assert deleted.status_code == 204
    assert request("GET", "/api/v1/plans").json() == []
    assert request("GET", "/api/v1/coach/conversations/latest").json() is None
    assert request("GET", "/api/v1/account/profile").status_code == 200
