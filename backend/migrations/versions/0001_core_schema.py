"""Create users, plans, tasks, and reflections.

Revision ID: 0001_core_schema
Revises:
Create Date: 2026-09-05
"""

from alembic import op
import sqlalchemy as sa


revision = "0001_core_schema"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("uid", sa.String(length=128), nullable=False),
        sa.Column("email", sa.String(length=320), nullable=True),
        sa.Column("display_name", sa.String(length=120), nullable=True),
        sa.Column("timezone", sa.String(length=64), server_default="UTC", nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.PrimaryKeyConstraint("uid", name="pk_users"),
        sa.UniqueConstraint("email", name="uq_users_email"),
    )
    op.create_table(
        "plans",
        sa.Column("id", sa.String(length=36), nullable=False),
        sa.Column("user_uid", sa.String(length=128), nullable=False),
        sa.Column("plan_date", sa.Date(), nullable=False),
        sa.Column("raw_input", sa.Text(), nullable=True),
        sa.Column("ai_summary", sa.Text(), nullable=True),
        sa.Column("status", sa.String(length=16), server_default="active", nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint("status IN ('draft', 'active', 'completed')", name="ck_plans_status_values"),
        sa.ForeignKeyConstraint(["user_uid"], ["users.uid"], name="fk_plans_user_uid_users", ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id", name="pk_plans"),
        sa.UniqueConstraint("user_uid", "plan_date", name="plan_user_date"),
    )
    op.create_index("ix_plans_plan_date", "plans", ["plan_date"])
    op.create_index("ix_plans_user_uid", "plans", ["user_uid"])
    op.create_table(
        "tasks",
        sa.Column("id", sa.String(length=36), nullable=False),
        sa.Column("plan_id", sa.String(length=36), nullable=False),
        sa.Column("title", sa.String(length=240), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("task_type", sa.String(length=64), server_default="Learning", nullable=False),
        sa.Column("priority", sa.Integer(), server_default="3", nullable=False),
        sa.Column("estimated_minutes", sa.Integer(), server_default="30", nullable=False),
        sa.Column("scheduled_start", sa.Time(), nullable=True),
        sa.Column("scheduled_end", sa.Time(), nullable=True),
        sa.Column("is_fixed_time", sa.Boolean(), server_default=sa.false(), nullable=False),
        sa.Column("is_break", sa.Boolean(), server_default=sa.false(), nullable=False),
        sa.Column("order_index", sa.Integer(), server_default="0", nullable=False),
        sa.Column("status", sa.String(length=16), server_default="pending", nullable=False),
        sa.Column("actual_minutes", sa.Integer(), nullable=True),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint("actual_minutes IS NULL OR actual_minutes >= 0", name="ck_tasks_actual_minutes_nonnegative"),
        sa.CheckConstraint("estimated_minutes >= 0", name="ck_tasks_estimated_minutes_nonnegative"),
        sa.CheckConstraint("priority BETWEEN 1 AND 5", name="ck_tasks_priority_range"),
        sa.CheckConstraint("status IN ('pending', 'in_progress', 'completed', 'failed')", name="ck_tasks_status_values"),
        sa.ForeignKeyConstraint(["plan_id"], ["plans.id"], name="fk_tasks_plan_id_plans", ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id", name="pk_tasks"),
    )
    op.create_index("ix_tasks_plan_id", "tasks", ["plan_id"])
    op.create_index("ix_tasks_status", "tasks", ["status"])
    op.create_table(
        "reflections",
        sa.Column("id", sa.String(length=36), nullable=False),
        sa.Column("user_uid", sa.String(length=128), nullable=False),
        sa.Column("task_id", sa.String(length=36), nullable=False),
        sa.Column("effort", sa.String(length=40), nullable=False),
        sa.Column("note", sa.Text(), server_default="", nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.ForeignKeyConstraint(["task_id"], ["tasks.id"], name="fk_reflections_task_id_tasks", ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["user_uid"], ["users.uid"], name="fk_reflections_user_uid_users", ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id", name="pk_reflections"),
    )
    op.create_index("ix_reflections_task_id", "reflections", ["task_id"])
    op.create_index("ix_reflections_user_uid", "reflections", ["user_uid"])


def downgrade() -> None:
    op.drop_index("ix_reflections_user_uid", table_name="reflections")
    op.drop_index("ix_reflections_task_id", table_name="reflections")
    op.drop_table("reflections")
    op.drop_index("ix_tasks_status", table_name="tasks")
    op.drop_index("ix_tasks_plan_id", table_name="tasks")
    op.drop_table("tasks")
    op.drop_index("ix_plans_user_uid", table_name="plans")
    op.drop_index("ix_plans_plan_date", table_name="plans")
    op.drop_table("plans")
    op.drop_table("users")
