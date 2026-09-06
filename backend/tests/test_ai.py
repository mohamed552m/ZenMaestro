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
from app.schemas.ai import PlanDraftResponse
from app.services.gemini import AiNotConfiguredError, GeminiService, get_gemini_service


engine = create_engine(
    "sqlite+pysqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)


def override_session() -> Generator[Session, None, None]:
    with SessionFactory() as session:
        yield session


class FakeAiService:
    configured = True
    model = "test-model"

    def chat(self, message, history, attachment=None):
        suffix = f" with {attachment.filename}" if attachment else ""
        return f"Coach received: {message} ({len(history)} previous turns){suffix}"

    def draft_plan(self, raw_input, existing_categories):
        return PlanDraftResponse(
            summary="A draft for review.",
            tasks=[
                {
                    "title": raw_input,
                    "task_type": existing_categories[0] if existing_categories else "Learning",
                    "priority": 3,
                    "estimated_minutes": 30,
                }
            ],
        )


class MissingAiService:
    configured = False
    model = "test-model"

    def chat(self, message, history, attachment=None):
        raise AiNotConfiguredError("Gemini is not configured.")


def request(
    method: str,
    path: str,
    json: dict | None = None,
    service=None,
    files=None,
    data=None,
    uid: str = "ai-user",
):
    async def send_request():
        app.dependency_overrides[get_db_session] = override_session
        app.dependency_overrides[get_current_user] = lambda: AuthenticatedUser(uid=uid)
        if service is not None:
            app.dependency_overrides[get_gemini_service] = lambda: service
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            return await client.request(method, path, json=json, files=files, data=data)

    try:
        return asyncio.run(send_request())
    finally:
        app.dependency_overrides.clear()


def setup_function() -> None:
    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)


def test_gemini_uses_fallback_after_temporary_model_error() -> None:
    service = GeminiService()
    service.models = ("primary-model", "fallback-model")
    attempts: list[str] = []

    class TemporaryModelError(RuntimeError):
        code = 503

    def request_model(model: str) -> str:
        attempts.append(model)
        if model == "primary-model":
            raise TemporaryModelError("temporarily unavailable")
        return "ok"

    assert service._with_model_fallback(request_model) == "ok"
    assert attempts == ["primary-model", "fallback-model"]


def test_ai_status_reports_missing_key_without_exposing_secrets() -> None:
    response = request("GET", "/api/v1/ai/status", service=MissingAiService())

    assert response.status_code == 200
    assert response.json()["configured"] is False
    assert "key" not in response.text.lower()


def test_coach_returns_503_until_key_is_configured() -> None:
    response = request(
        "POST",
        "/api/v1/coach/chat",
        {"message": "Help me study"},
        service=MissingAiService(),
    )

    assert response.status_code == 503
    assert response.json()["detail"] == "AI is not configured on the server yet."


def test_coach_accepts_only_explicit_chat_payload() -> None:
    response = request(
        "POST",
        "/api/v1/coach/chat",
        {
            "message": "Help me focus",
            "history": [{"role": "user", "content": "Hello"}],
        },
        service=FakeAiService(),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["reply"] == "Coach received: Help me focus (1 previous turns)"
    assert len(body["conversation_id"]) == 36

    latest = request("GET", "/api/v1/coach/conversations/latest")
    assert latest.status_code == 200
    assert [item["role"] for item in latest.json()["messages"]] == ["user", "model"]
    assert latest.json()["messages"][0]["content"] == "Help me focus"


def test_coach_conversations_are_private() -> None:
    created = request(
        "POST",
        "/api/v1/coach/chat",
        {"message": "My private study goal"},
        service=FakeAiService(),
        uid="first-user",
    )
    assert created.status_code == 200
    assert request(
        "GET",
        "/api/v1/coach/conversations/latest",
        uid="second-user",
    ).json() is None


def test_coach_accepts_an_explicit_text_attachment() -> None:
    response = request(
        "POST",
        "/api/v1/coach/chat/attachment",
        service=FakeAiService(),
        data={"message": "Summarize this", "history_json": "[]"},
        files={"attachment": ("notes.txt", b"Kotlin coroutines", "text/plain")},
    )
    assert response.status_code == 200
    assert response.json()["reply"].endswith("with notes.txt")
    latest = request("GET", "/api/v1/coach/conversations/latest").json()
    assert latest["messages"][0]["attachment_name"] == "notes.txt"


def test_coach_rejects_unsupported_attachment_types() -> None:
    response = request(
        "POST",
        "/api/v1/coach/chat/attachment",
        service=FakeAiService(),
        data={"message": "Open this", "history_json": "[]"},
        files={"attachment": ("program.exe", b"unsafe", "application/octet-stream")},
    )
    assert response.status_code == 415


def test_planner_returns_structured_draft_without_saving_it() -> None:
    response = request(
        "POST",
        "/api/v1/planner/draft",
        {"raw_input": "Study Kotlin", "existing_categories": ["Study"]},
        service=FakeAiService(),
    )

    assert response.status_code == 200
    assert response.json()["tasks"] == [
        {
            "title": "Study Kotlin",
            "task_type": "Study",
            "priority": 3,
            "estimated_minutes": 30,
        }
    ]
