import asyncio
from types import SimpleNamespace

from httpx import ASGITransport, AsyncClient

from app.api.dependencies import auth as auth_dependencies
from app.main import app


def request_me(token: str | None = None):
    async def send_request():
        headers = {"Authorization": f"Bearer {token}"} if token else None
        transport = ASGITransport(app=app)
        async with AsyncClient(
            transport=transport,
            base_url="http://testserver",
        ) as client:
            return await client.get("/api/v1/account/me", headers=headers)

    return asyncio.run(send_request())


def request_me_with_app_check(token: str, app_check_token: str):
    async def send_request():
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            return await client.get(
                "/api/v1/account/me",
                headers={
                    "Authorization": f"Bearer {token}",
                    "X-Firebase-AppCheck": app_check_token,
                },
            )

    return asyncio.run(send_request())


def test_account_endpoint_requires_token() -> None:
    response = request_me()

    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"


def test_account_endpoint_uses_verified_firebase_claims(monkeypatch) -> None:
    monkeypatch.setattr(
        auth_dependencies,
        "verify_firebase_id_token",
        lambda token: {
            "uid": "firebase-user-123",
            "email": "learner@example.com",
            "name": "Zen Learner",
            "email_verified": True,
        },
    )

    response = request_me("verified-test-token")

    assert response.status_code == 200
    assert response.json() == {
        "uid": "firebase-user-123",
        "email": "learner@example.com",
        "name": "Zen Learner",
        "email_verified": True,
    }


def test_account_endpoint_rejects_invalid_token(monkeypatch) -> None:
    def reject_token(token: str):
        raise ValueError("invalid token")

    monkeypatch.setattr(
        auth_dependencies,
        "verify_firebase_id_token",
        reject_token,
    )

    response = request_me("invalid-test-token")

    assert response.status_code == 401


def test_app_check_can_be_required_for_production(monkeypatch) -> None:
    monkeypatch.setattr(
        auth_dependencies,
        "get_settings",
        lambda: SimpleNamespace(require_app_check=True),
    )
    monkeypatch.setattr(
        auth_dependencies,
        "verify_firebase_id_token",
        lambda token: {"uid": "protected-user"},
    )
    monkeypatch.setattr(
        auth_dependencies,
        "verify_firebase_app_check_token",
        lambda token: {"app_id": "test-app"},
    )

    assert request_me("id-token").status_code == 401
    assert request_me_with_app_check("id-token", "app-check-token").status_code == 200
