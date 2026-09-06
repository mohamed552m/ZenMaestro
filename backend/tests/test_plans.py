import asyncio
from collections.abc import Generator
from datetime import date

from httpx import ASGITransport, AsyncClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from app import models  # noqa: F401
from app.api.dependencies.auth import AuthenticatedUser, get_current_user
from app.db.base import Base
from app.db.session import get_db_session
from app.main import app


engine = create_engine(
    "sqlite+pysqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)


def override_session() -> Generator[Session, None, None]:
    with SessionFactory() as session:
        yield session


def request_as(user_uid: str, method: str, path: str, json: dict | None = None):
    async def send_request():
        app.dependency_overrides[get_db_session] = override_session
        app.dependency_overrides[get_current_user] = lambda: AuthenticatedUser(
            uid=user_uid,
            email=f"{user_uid}@example.com",
            name=f"Learner {user_uid}",
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


def test_user_can_create_read_update_and_delete_own_task() -> None:
    task_id = "7c017039-e509-42a4-bac6-034a722351f2"
    created = request_as(
        "user-one",
        "POST",
        "/api/v1/plans/2026-09-05/tasks",
        {
            "id": task_id,
            "title": "  Study FastAPI  ",
            "task_type": "Study",
            "priority": 2,
            "estimated_minutes": 45,
        },
    )
    assert created.status_code == 201
    assert created.json()["title"] == "Study FastAPI"
    assert created.json()["status"] == "pending"

    plan = request_as("user-one", "GET", "/api/v1/plans/2026-09-05")
    assert plan.status_code == 200
    assert plan.json()["tasks"][0]["id"] == task_id

    updated = request_as(
        "user-one",
        "PATCH",
        f"/api/v1/tasks/{task_id}",
        {"status": "completed", "actual_minutes": 42},
    )
    assert updated.status_code == 200
    assert updated.json()["status"] == "completed"
    assert updated.json()["completed_at"] is not None

    deleted = request_as("user-one", "DELETE", f"/api/v1/tasks/{task_id}")
    assert deleted.status_code == 204
    assert request_as("user-one", "GET", "/api/v1/plans/2026-09-05").json()["tasks"] == []


def test_user_cannot_access_another_users_plan_or_task() -> None:
    created = request_as(
        "owner",
        "POST",
        "/api/v1/plans/2026-09-05/tasks",
        {"title": "Private learning task"},
    )
    task_id = created.json()["id"]

    assert request_as("other", "GET", "/api/v1/plans/2026-09-05").status_code == 404
    assert request_as(
        "other",
        "PATCH",
        f"/api/v1/tasks/{task_id}",
        {"status": "completed"},
    ).status_code == 404
    assert request_as("other", "DELETE", f"/api/v1/tasks/{task_id}").status_code == 404


def test_plan_list_filters_dates_and_validates_range() -> None:
    for plan_date in (date(2026, 9, 4), date(2026, 9, 5)):
        response = request_as(
            "filter-user",
            "PUT",
            f"/api/v1/plans/{plan_date.isoformat()}",
            {"status": "active"},
        )
        assert response.status_code == 200

    filtered = request_as(
        "filter-user",
        "GET",
        "/api/v1/plans?start_date=2026-09-05&end_date=2026-09-05",
    )
    assert [plan["plan_date"] for plan in filtered.json()] == ["2026-09-05"]

    invalid = request_as(
        "filter-user",
        "GET",
        "/api/v1/plans?start_date=2026-09-06&end_date=2026-09-05",
    )
    assert invalid.status_code == 422


def test_task_snapshot_put_is_idempotent_and_can_move_dates() -> None:
    task_id = "93dcb3b2-7263-4147-846e-96ad3506a6f0"
    payload = {
        "title": "User-created task",
        "task_type": "Practice",
        "priority": 1,
        "estimated_minutes": 25,
        "status": "pending",
    }
    first = request_as(
        "sync-user",
        "PUT",
        f"/api/v1/plans/2026-09-05/tasks/{task_id}",
        payload,
    )
    second = request_as(
        "sync-user",
        "PUT",
        f"/api/v1/plans/2026-09-06/tasks/{task_id}",
        {**payload, "status": "completed"},
    )
    assert first.status_code == 200
    assert second.status_code == 200
    assert second.json()["id"] == task_id
    assert second.json()["status"] == "completed"
    assert request_as("sync-user", "GET", "/api/v1/plans/2026-09-05").json()["tasks"] == []
    moved_plan = request_as("sync-user", "GET", "/api/v1/plans/2026-09-06")
    assert [task["id"] for task in moved_plan.json()["tasks"]] == [task_id]
