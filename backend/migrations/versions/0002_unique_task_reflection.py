"""Allow one reflection per completed task.

Revision ID: 0002_unique_task_reflection
Revises: 0001_core_schema
Create Date: 2026-09-05
"""

from alembic import op


revision = "0002_unique_task_reflection"
down_revision = "0001_core_schema"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("reflections") as batch_op:
        batch_op.create_unique_constraint("reflection_task", ["task_id"])


def downgrade() -> None:
    with op.batch_alter_table("reflections") as batch_op:
        batch_op.drop_constraint("reflection_task", type_="unique")
