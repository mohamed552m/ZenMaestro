from datetime import date

from pydantic import BaseModel, Field


class ProgressSummary(BaseModel):
    as_of: date
    total_tasks: int = Field(ge=0)
    completed_tasks: int = Field(ge=0)
    focused_minutes: int = Field(ge=0)
    current_streak_days: int = Field(ge=0)
    week_start: date
    week_end: date
    week_tasks: int = Field(ge=0)
    week_completed_tasks: int = Field(ge=0)
    week_completion_percent: int = Field(ge=0, le=100)
