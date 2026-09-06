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


engine = create_engine(
    "sqlite+pysqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)


def override_session() -> Generator[Session, None, None]:
    with SessionFactory() as session:
        yield session


def request(method: str, path: str, json: dict | None = None, uid: str = "learner"):
    async def send_request():
        app.dependency_overrides[get_db_session] = override_session
        app.dependency_overrides[get_current_user] = lambda: AuthenticatedUser(uid=uid)
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


def add_task(task_id: str, plan_date: str, duration: int = 30):
    return request(
        "POST",
        f"/api/v1/plans/{plan_date}/tasks",
        {"id": task_id, "title": "Learning session", "estimated_minutes": duration},
    )


def test_reflection_is_idempotent_and_private() -> None:
    task_id = "4038e88c-940b-4acf-baa8-37135f8af721"
    assert add_task(task_id, "2026-09-05").status_code == 201
    payload = {
        "effort": "About right",
        "note": "Good pace",
        "completed_at": "2026-09-05T17:30:00Z",
    }
    first = request("PUT", f"/api/v1/tasks/{task_id}/reflection", payload)
    second = request(
        "PUT",
        f"/api/v1/tasks/{task_id}/reflection",
        {**payload, "note": "Updated note"},
    )
    assert first.status_code == 200
    assert second.status_code == 200
    assert second.json()["id"] == first.json()["id"]
    assert second.json()["note"] == "Updated note"
    assert len(request("GET", "/api/v1/reflections").json()) == 1
    assert request(
        "PUT",
        f"/api/v1/tasks/{task_id}/reflection",
        payload,
        uid="another-user",
    ).status_code == 404


def test_progress_summary_matches_completed_learning_tasks() -> None:
    yesterday_task = "f4dbf93a-b799-4211-9fb9-1fed741ce76c"
    today_task = "09f5c407-bc8f-42a8-a4d9-a930227850f1"
    pending_task = "c8d893f8-0b1f-4278-a81a-802f2253b4a7"
    add_task(yesterday_task, "2026-09-04", 20)
    add_task(today_task, "2026-09-05", 40)
    add_task(pending_task, "2026-09-05", 60)
    request("PATCH", f"/api/v1/tasks/{yesterday_task}", {"status": "completed"})
    request(
        "PATCH",
        f"/api/v1/tasks/{today_task}",
        {"status": "completed", "actual_minutes": 35},
    )

    response = request("GET", "/api/v1/progress/summary?as_of=2026-09-05")
    assert response.status_code == 200
    assert response.json() == {
        "as_of": "2026-09-05",
        "total_tasks": 3,
        "completed_tasks": 2,
        "focused_minutes": 55,
        "current_streak_days": 2,
        "week_start": "2026-08-31",
        "week_end": "2026-09-06",
        "week_tasks": 3,
        "week_completed_tasks": 2,
        "week_completion_percent": 66,
    }
