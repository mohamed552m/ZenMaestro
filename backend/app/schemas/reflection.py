from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator


class ReflectionUpsert(BaseModel):
    effort: str = Field(min_length=1, max_length=40)
    note: str = Field(default="", max_length=5000)
    completed_at: datetime

    @field_validator("effort")
    @classmethod
    def normalize_effort(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("Effort cannot be blank.")
        return value

    @field_validator("note")
    @classmethod
    def normalize_note(cls, value: str) -> str:
        return value.strip()


class ReflectionResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    task_id: str
    effort: str
    note: str
    completed_at: datetime
    created_at: datetime
    updated_at: datetime
