from datetime import datetime, time
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator


TaskStatus = Literal["pending", "in_progress", "completed", "failed"]


class TaskCreate(BaseModel):
    id: UUID | None = None
    title: str = Field(min_length=1, max_length=240)
    description: str | None = Field(default=None, max_length=5000)
    task_type: str = Field(default="Learning", min_length=1, max_length=64)
    priority: int = Field(default=3, ge=1, le=5)
    estimated_minutes: int = Field(default=30, ge=0, le=1440)
    scheduled_start: time | None = None
    scheduled_end: time | None = None
    is_fixed_time: bool = False
    is_break: bool = False
    order_index: int = Field(default=0, ge=0)

    @model_validator(mode="after")
    def normalize_user_input(self) -> "TaskCreate":
        self.title = self.title.strip()
        self.task_type = self.task_type.strip()
        if not self.title:
            raise ValueError("Task title cannot be blank.")
        if not self.task_type:
            raise ValueError("Task type cannot be blank.")
        if self.description is not None:
            self.description = self.description.strip() or None
        if (self.scheduled_start is None) != (self.scheduled_end is None):
            raise ValueError("Scheduled start and end must be provided together.")
        if self.is_break:
            self.is_fixed_time = True
        return self


class TaskUpdate(BaseModel):
    title: str | None = Field(default=None, min_length=1, max_length=240)
    description: str | None = Field(default=None, max_length=5000)
    task_type: str | None = Field(default=None, min_length=1, max_length=64)
    priority: int | None = Field(default=None, ge=1, le=5)
    estimated_minutes: int | None = Field(default=None, ge=0, le=1440)
    scheduled_start: time | None = None
    scheduled_end: time | None = None
    is_fixed_time: bool | None = None
    is_break: bool | None = None
    order_index: int | None = Field(default=None, ge=0)
    status: TaskStatus | None = None
    actual_minutes: int | None = Field(default=None, ge=0, le=1440)

    @model_validator(mode="after")
    def normalize_user_input(self) -> "TaskUpdate":
        if self.title is not None:
            self.title = self.title.strip()
            if not self.title:
                raise ValueError("Task title cannot be blank.")
        if self.task_type is not None:
            self.task_type = self.task_type.strip()
            if not self.task_type:
                raise ValueError("Task type cannot be blank.")
        if self.description is not None:
            self.description = self.description.strip() or None
        supplied = self.model_fields_set
        if ("scheduled_start" in supplied) != ("scheduled_end" in supplied):
            raise ValueError("Scheduled start and end must be updated together.")
        if self.is_break is True:
            self.is_fixed_time = True
        return self


class TaskSnapshot(TaskCreate):
    status: TaskStatus = "pending"
    actual_minutes: int | None = Field(default=None, ge=0, le=1440)


class TaskResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    title: str
    description: str | None
    task_type: str
    priority: int
    estimated_minutes: int
    scheduled_start: time | None
    scheduled_end: time | None
    is_fixed_time: bool
    is_break: bool
    order_index: int
    status: TaskStatus
    actual_minutes: int | None
    started_at: datetime | None
    completed_at: datetime | None
    created_at: datetime
    updated_at: datetime
