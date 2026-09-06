from sqlalchemy import create_engine, inspect

from app import models  # noqa: F401
from app.db.base import Base


def test_core_schema_can_be_created() -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")
    Base.metadata.create_all(engine)

    assert set(inspect(engine).get_table_names()) == {
        "plans",
        "reflections",
        "tasks",
        "users",
        "chat_conversations",
        "chat_messages",
    }
