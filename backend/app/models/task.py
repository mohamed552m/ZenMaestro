from datetime import datetime, time

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    Time,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base
from app.models.common import TimestampMixin


class Task(TimestampMixin, Base):
    __tablename__ = "tasks"
    __table_args__ = (
        CheckConstraint("priority BETWEEN 1 AND 5", name="priority_range"),
        CheckConstraint("estimated_minutes >= 0", name="estimated_minutes_nonnegative"),
        CheckConstraint("actual_minutes IS NULL OR actual_minutes >= 0", name="actual_minutes_nonnegative"),
        CheckConstraint(
            "status IN ('pending', 'in_progress', 'completed', 'failed')",
            name="status_values",
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    plan_id: Mapped[str] = mapped_column(
        ForeignKey("plans.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    title: Mapped[str] = mapped_column(String(240), nullable=False)
    description: Mapped[str | None] = mapped_column(Text)
    task_type: Mapped[str] = mapped_column(
        String(64),
        nullable=False,
        default="Learning",
        server_default="Learning",
    )
    priority: Mapped[int] = mapped_column(Integer, nullable=False, default=3, server_default="3")
    estimated_minutes: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=30,
        server_default="30",
    )
    scheduled_start: Mapped[time | None] = mapped_column(Time)
    scheduled_end: Mapped[time | None] = mapped_column(Time)
    is_fixed_time: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False, server_default="false")
    is_break: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False, server_default="false")
    order_index: Mapped[int] = mapped_column(Integer, nullable=False, default=0, server_default="0")
    status: Mapped[str] = mapped_column(
        String(16),
        nullable=False,
        default="pending",
        server_default="pending",
        index=True,
    )
    actual_minutes: Mapped[int | None] = mapped_column(Integer)
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    plan = relationship("Plan", back_populates="tasks")
    reflections = relationship(
        "Reflection",
        back_populates="task",
        cascade="all, delete-orphan",
    )
