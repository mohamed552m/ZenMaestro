from datetime import date

from sqlalchemy import CheckConstraint, Date, ForeignKey, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base
from app.models.common import TimestampMixin


class Plan(TimestampMixin, Base):
    __tablename__ = "plans"
    __table_args__ = (
        UniqueConstraint("user_uid", "plan_date", name="plan_user_date"),
        CheckConstraint(
            "status IN ('draft', 'active', 'completed')",
            name="status_values",
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    user_uid: Mapped[str] = mapped_column(
        ForeignKey("users.uid", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    plan_date: Mapped[date] = mapped_column(Date, nullable=False, index=True)
    raw_input: Mapped[str | None] = mapped_column(Text)
    ai_summary: Mapped[str | None] = mapped_column(Text)
    status: Mapped[str] = mapped_column(
        String(16),
        nullable=False,
        default="active",
        server_default="active",
    )

    user = relationship("User", back_populates="plans")
    tasks = relationship(
        "Task",
        back_populates="plan",
        cascade="all, delete-orphan",
        order_by="Task.order_index",
    )
