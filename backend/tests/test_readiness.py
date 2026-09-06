import asyncio
from collections.abc import Generator

from httpx import ASGITransport, AsyncClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from app.db.session import get_db_session
from app.main import app


engine = create_engine(
    "sqlite+pysqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
SessionFactory = sessionmaker(bind=engine)


def override_session() -> Generator[Session, None, None]:
    with SessionFactory() as session:
        yield session


def test_readiness_checks_database_connection() -> None:
    async def request_ready():
        app.dependency_overrides[get_db_session] = override_session
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            return await client.get("/api/v1/ready")

    try:
        response = asyncio.run(request_ready())
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json() == {"status": "ready"}
