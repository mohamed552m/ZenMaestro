from datetime import date, datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.task import TaskResponse


PlanStatus = Literal["draft", "active", "completed"]


class PlanUpsert(BaseModel):
    raw_input: str | None = Field(default=None, max_length=10000)
    ai_summary: str | None = Field(default=None, max_length=10000)
    status: PlanStatus = "active"


class PlanResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    plan_date: date
    raw_input: str | None
    ai_summary: str | None
    status: PlanStatus
    created_at: datetime
    updated_at: datetime
    tasks: list[TaskResponse]
