from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field, field_validator


class ChatTurn(BaseModel):
    role: Literal["user", "model"]
    content: str = Field(min_length=1, max_length=4000)

    @field_validator("content")
    @classmethod
    def normalize_content(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("Message cannot be blank.")
        return value


class CoachChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    history: list[ChatTurn] = Field(default_factory=list, max_length=20)
    conversation_id: str | None = Field(default=None, min_length=36, max_length=36)

    @field_validator("message")
    @classmethod
    def normalize_message(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("Message cannot be blank.")
        return value


class CoachChatResponse(BaseModel):
    reply: str
    conversation_id: str


class ChatMessageResponse(BaseModel):
    role: Literal["user", "model"]
    content: str
    attachment_name: str | None = None
    attachment_mime: str | None = None
    created_at: datetime


class ChatConversationResponse(BaseModel):
    id: str
    title: str
    messages: list[ChatMessageResponse]
    created_at: datetime
    updated_at: datetime


class PlanDraftRequest(BaseModel):
    raw_input: str = Field(min_length=1, max_length=10000)
    existing_categories: list[str] = Field(default_factory=list, max_length=50)

    @field_validator("raw_input")
    @classmethod
    def normalize_input(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("Plan input cannot be blank.")
        return value

    @field_validator("existing_categories")
    @classmethod
    def normalize_categories(cls, values: list[str]) -> list[str]:
        normalized: list[str] = []
        for value in values:
            value = value.strip()
            if value and value not in normalized:
                normalized.append(value[:64])
        return normalized


class DraftTask(BaseModel):
    title: str = Field(min_length=1, max_length=240)
    task_type: str = Field(default="Learning", min_length=1, max_length=64)
    priority: int = Field(default=3, ge=1, le=5)
    estimated_minutes: int = Field(default=30, ge=0, le=1440)


class PlanDraftResponse(BaseModel):
    summary: str = Field(default="", max_length=2000)
    tasks: list[DraftTask] = Field(default_factory=list, max_length=50)


class AiStatusResponse(BaseModel):
    configured: bool
    model: str
