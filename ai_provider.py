"""Shared, side-effect-free configuration for Gemini-backed features."""

from __future__ import annotations

import logging
import os
import re

from backend_errors import ConfigurationError


DEFAULT_FAST_MODEL = "gemini-3.1-flash-lite"
DEFAULT_FALLBACK_MODEL = "gemini-3.5-flash"
DEFAULT_COACH_MODEL = "gemini-3.8-flash"

logger = logging.getLogger("coachai.ai")


def configured_model(env_name: str, default: str) -> str:
    """Read and validate one bounded provider model identifier."""
    value = os.getenv(env_name, default).strip()
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", value):
        raise ConfigurationError(f"{env_name} is invalid.")
    return value


def google_api_key() -> str | None:
    """Return the configured Gemini key without exposing it to callers or logs."""
    key = (os.getenv("GOOGLE_API_KEY") or os.getenv("GEMINI_API_KEY") or "").strip()
    return key or None


def provider_status_code(exc: BaseException) -> int | None:
    """Find a provider HTTP status on an exception or its bounded cause chain."""
    current: BaseException | None = exc
    for _ in range(5):
        if current is None:
            break
        for attribute in ("status_code", "code"):
            value = getattr(current, attribute, None)
            if isinstance(value, int) and 100 <= value <= 599:
                return value
        current = current.__cause__ or current.__context__
    return None


def is_transient_provider_error(exc: Exception) -> bool:
    """Return whether a provider failure is safe to retry on one fallback."""
    status_code = provider_status_code(exc)
    if status_code in {408, 429, 500, 502, 503, 504}:
        return True
    return type(exc).__name__.lower() in {
        "connecttimeout",
        "deadlineexceeded",
        "readtimeout",
        "resourceexhausted",
        "servererror",
        "serviceunavailable",
        "timeouterror",
        "timeoutexception",
    }


def is_fallback_eligible_provider_error(exc: Exception) -> bool:
    """Return whether a second configured model may safely handle the request."""
    return is_transient_provider_error(exc) or provider_status_code(exc) == 404


def log_provider_failure(feature: str, model: str, exc: BaseException) -> None:
    """Log bounded diagnostics without provider text, prompts, or credentials."""
    logger.warning(
        "ai_provider_failure feature=%s model=%s error_type=%s status=%s",
        feature,
        model,
        type(exc).__name__,
        provider_status_code(exc),
    )
