"""Shared, side-effect-free configuration for Gemini-backed features."""

from __future__ import annotations

import os
import re

from backend_errors import ConfigurationError


DEFAULT_FAST_MODEL = "gemini-3.1-flash-lite"
DEFAULT_FALLBACK_MODEL = "gemini-3.5-flash"


def configured_model(env_name: str, default: str) -> str:
    """Read and validate one bounded provider model identifier."""
    value = os.getenv(env_name, default).strip()
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", value):
        raise ConfigurationError(f"{env_name} is invalid.")
    return value


def is_transient_provider_error(exc: Exception) -> bool:
    """Return whether a provider failure is safe to retry on one fallback."""
    status_code = getattr(exc, "status_code", None)
    if status_code in {408, 429, 500, 502, 503, 504}:
        return True
    return type(exc).__name__.lower() in {
        "resourceexhausted",
        "servererror",
        "serviceunavailable",
    }
