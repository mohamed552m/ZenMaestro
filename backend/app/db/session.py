from collections.abc import Generator
from functools import lru_cache

from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.core.config import get_settings


@lru_cache
def get_engine() -> Engine:
    database_url = get_settings().database_url
    if database_url is None:
        raise RuntimeError("ZENMAESTRO_DATABASE_URL is required for database access.")
    return create_engine(
        database_url.get_secret_value(),
        pool_pre_ping=True,
    )


def get_db_session() -> Generator[Session, None, None]:
    session_factory = sessionmaker(
        bind=get_engine(),
        autoflush=False,
        expire_on_commit=False,
    )
    with session_factory() as session:
        yield session
