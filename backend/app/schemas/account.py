from datetime import time
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, field_validator


class AccountProfileResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    uid: str
    email: str | None
    display_name: str | None
    email_verified: bool
    timezone: str
    learning_day_start: time
    reminders_enabled: bool


class AccountPreferencesUpdate(BaseModel):
    timezone: str | None = None
    learning_day_start: time | None = None
    reminders_enabled: bool | None = None

    @field_validator("timezone")
    @classmethod
    def validate_timezone(cls, value: str | None) -> str | None:
        if value is None:
            return None
        value = value.strip()
        if not value:
            raise ValueError("Timezone cannot be blank.")
        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as error:
            raise ValueError("Timezone must be a valid IANA timezone.") from error
        return value
