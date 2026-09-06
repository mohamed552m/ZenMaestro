"""add user learning preferences

Revision ID: 0003_user_learning_preferences
Revises: 0002_unique_task_reflection
Create Date: 2026-09-05
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op


revision: str = "0003_user_learning_preferences"
down_revision: str | None = "0002_unique_task_reflection"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    with op.batch_alter_table("users") as batch_op:
        batch_op.add_column(
            sa.Column(
                "learning_day_start",
                sa.Time(),
                nullable=False,
                server_default="09:00:00",
            )
        )
        batch_op.add_column(
            sa.Column(
                "reminders_enabled",
                sa.Boolean(),
                nullable=False,
                server_default=sa.true(),
            )
        )


def downgrade() -> None:
    with op.batch_alter_table("users") as batch_op:
        batch_op.drop_column("reminders_enabled")
        batch_op.drop_column("learning_day_start")
