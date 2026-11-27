"""
CoachAI – Voice Service (Speech-to-Text via Gemini)
======================================================

Transcribes a voice recording (bytes from Streamlit's st.audio_input())
into plain text using Gemini's native audio understanding.

The transcribed text is handed off as-is to the existing PlannerEngine
(planner.py) exactly like typed free-form text — this module never
splits tasks, assigns categories, or talks to the database. It has
exactly one job: audio in, text out.

Usage:
    from voice_service import transcribe_audio

    text = transcribe_audio(audio_bytes, mime_type="audio/wav")
    result = generate_today_plan(raw_input=text, user_id=user_id)
"""

from __future__ import annotations

import os
import time
from typing import Any, Optional

from dotenv import load_dotenv
from google import genai
from google.genai import types

from ai_provider import (
    DEFAULT_FALLBACK_MODEL,
    DEFAULT_FAST_MODEL,
    configured_model,
    google_api_key,
    is_fallback_eligible_provider_error,
    log_provider_failure,
)
from backend_errors import ConfigurationError
from domain_validation import (
    MAX_AI_OUTPUT_TOKENS,
    MAX_AUDIO_BYTES,
    MAX_TRANSCRIPT_CHARS,
)
from operational_metrics import BackendMetrics


# ---------------------------------------------------------------------------
# Environment Setup
# ---------------------------------------------------------------------------

load_dotenv()

_GOOGLE_API_KEY: Optional[str] = os.getenv("GOOGLE_API_KEY")


def _require_google_api_key() -> str:
    """Return the Gemini API key, validating lazily at call time.

    Import of this module must not fail just because an optional API key
    is missing from the environment. The key (and genai.configure()) is
    only required once transcription is actually attempted.
    """
    key = google_api_key() or _GOOGLE_API_KEY
    if not key:
        raise ConfigurationError("The AI provider is not configured.")
    return key


def _ai_timeout_seconds() -> float:
    try:
        value = float(os.getenv("COACHAI_AI_TIMEOUT_SECONDS", "20"))
    except ValueError as exc:
        raise ConfigurationError("COACHAI_AI_TIMEOUT_SECONDS must be numeric.") from exc
    if not 0.1 <= value <= 120:
        raise ConfigurationError(
            "COACHAI_AI_TIMEOUT_SECONDS must be between 0.1 and 120 seconds."
        )
    return value


def _model_name() -> str:
    """Return one bounded model identifier for this deployment."""
    return configured_model("COACHAI_VOICE_MODEL", _DEFAULT_MODEL_NAME)


def _fallback_model_name() -> str:
    return configured_model(
        "COACHAI_VOICE_FALLBACK_MODEL",
        _DEFAULT_FALLBACK_MODEL_NAME,
    )


def _new_client(api_key: str) -> genai.Client:
    """Create a supported SDK client with finite request and retry bounds."""
    timeout_ms = max(100, int(_ai_timeout_seconds() * 1_000))
    return genai.Client(
        api_key=api_key,
        http_options=types.HttpOptions(
            api_version="v1",
            timeout=timeout_ms,
            retry_options=types.HttpRetryOptions(
                attempts=1,
                initial_delay=0.5,
                max_delay=2.0,
                exp_base=2.0,
                jitter=0.2,
                http_status_codes=[408, 429, 500, 502, 503, 504],
            ),
        ),
    )


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Same model family used by planner.py / recommendation.py, for one
# consistent Gemini version across the whole app.
_DEFAULT_MODEL_NAME: str = DEFAULT_FAST_MODEL
_DEFAULT_FALLBACK_MODEL_NAME: str = DEFAULT_FALLBACK_MODEL

_ALLOWED_AUDIO_MIME_TYPES = frozenset({
    "audio/flac",
    "audio/mp3",
    "audio/mp4",
    "audio/mpeg",
    "audio/ogg",
    "audio/wav",
    "audio/webm",
    "audio/x-wav",
})

_TRANSCRIBE_PROMPT: str = (
    "You are an automatic speech recognition (ASR) engine, not an AI "
    "assistant. Your only task is to transcribe the audio exactly as "
    "spoken. The speaker may use English, Arabic, or Egyptian Arabic "
    "mixed with English within the same sentence. Preserve the "
    "original language exactly as spoken. Never translate, "
    "paraphrase, summarize, or rewrite anything. Do not add "
    "punctuation the speaker didn't clearly imply through pauses or "
    "intonation — this transcript is used downstream to split the "
    "speech into individual tasks, so invented punctuation can "
    "silently merge or split tasks incorrectly. "
    "Preserve English technical terms exactly as they are spoken, "
    "including but not limited to: Machine Learning, Deep Learning, "
    "Artificial Intelligence, AI, LLM, Python, SQL, Database, GitHub, "
    "Git, VS Code, Streamlit, Docker, API, JSON, CoachAI, LangChain, "
    "Gemini, FastAPI, Linux, C++, Java, JavaScript, React, TensorFlow, "
    "PyTorch. Do not convert English technical words into Arabic "
    "phonetic spellings. For example, transcribe 'Machine Learning' "
    "as 'Machine Learning', not 'ماشين ليرنينج'. "
    "If any portion of the recording is unclear, noisy, or silent, do "
    "not guess or invent missing words. Transcribe only what is "
    "confidently audible. Never infer, normalize, or complete dates, "
    "times, or durations beyond exactly what was spoken — e.g. if the "
    "speaker says a vague time, transcribe that vague phrasing "
    "verbatim rather than converting it to a precise clock time. "
    "Do not add explanations, labels, comments, markdown, quotation "
    "marks, or formatting. Return only the raw transcript."
)


# ---------------------------------------------------------------------------
# Errors
# ---------------------------------------------------------------------------

class VoiceTranscriptionError(Exception):
    """Raised when Gemini fails to transcribe the given audio."""


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def transcribe_audio(
    audio_bytes: bytes,
    mime_type: str = "audio/wav",
    *,
    metrics: BackendMetrics | None = None,
) -> str:
    """
    Transcribe raw audio bytes into plain text using Gemini.

    Sends the audio as an inline blob on the same generate_content
    endpoint already used successfully by planner.py / recommendation.py
    — the alternative genai.upload_file() (File API) path was tried and
    rejected the same API key ("API key not valid"), since it validates
    keys differently than plain generateContent calls. Inline data needs
    no extra key permissions and no async processing wait.

    Args:
        audio_bytes: Raw audio bytes, e.g. the value returned by
            Streamlit's st.audio_input() widget.
        mime_type: MIME type of the audio, ideally read from the
            recorder itself (e.g. audio_value.type) rather than
            assumed — a mismatched mime_type is a common reason Gemini
            silently returns an empty (or, worse, hallucinated)
            response for otherwise-valid audio.

    Returns:
        The transcribed text, stripped of leading/trailing whitespace.

    Raises:
        ValueError: If audio_bytes is empty.
        VoiceTranscriptionError: If Gemini fails, or returns no text
            (e.g. silence, a blocked prompt, or a mismatched format).
    """
    if not audio_bytes:
        raise ValueError("audio_bytes cannot be empty.")
    if not isinstance(audio_bytes, (bytes, bytearray, memoryview)):
        raise ValueError("audio_bytes must contain binary audio data.")
    if len(audio_bytes) > MAX_AUDIO_BYTES:
        raise ValueError(
            f"Audio cannot exceed {MAX_AUDIO_BYTES} bytes."
        )
    normalized_mime = str(mime_type or "").split(";", 1)[0].strip().lower()
    if normalized_mime not in _ALLOWED_AUDIO_MIME_TYPES:
        raise ValueError("Unsupported audio MIME type.")

    api_key = _require_google_api_key()
    model_name = _model_name()
    fallback_model_name = _fallback_model_name()
    metric_sink = metrics or BackendMetrics()
    started = time.monotonic()
    client: genai.Client | None = None

    try:
        client = _new_client(api_key)
        contents = [
            types.Part.from_text(text=_TRANSCRIBE_PROMPT),
            types.Part.from_bytes(data=bytes(audio_bytes), mime_type=normalized_mime),
        ]
        config = types.GenerateContentConfig(
            temperature=0.0,
            candidate_count=1,
            max_output_tokens=min(MAX_AI_OUTPUT_TOKENS, 4096),
        )
        try:
            response = client.models.generate_content(
                model=model_name,
                contents=contents,
                config=config,
            )
        except Exception as exc:
            log_provider_failure("voice", model_name, exc)
            metric_sink.ai_call(
                "google",
                model_name,
                succeeded=False,
                duration_seconds=time.monotonic() - started,
            )
            if not is_fallback_eligible_provider_error(exc) or model_name == fallback_model_name:
                raise
            started = time.monotonic()
            model_name = fallback_model_name
            response = client.models.generate_content(
                model=model_name,
                contents=contents,
                config=config,
            )
    except Exception as exc:
        log_provider_failure("voice", model_name, exc)
        metric_sink.ai_call(
            "google",
            model_name,
            succeeded=False,
            duration_seconds=time.monotonic() - started,
        )
        raise VoiceTranscriptionError(
            "The audio provider could not transcribe this recording."
        ) from exc
    finally:
        if client is not None:
            try:
                client.close()
            except Exception:
                # Closing a completed request must not change its outcome.
                pass

    text = _extract_text(response)
    if not text:
        metric_sink.ai_call(
            "google",
            model_name,
            succeeded=False,
            duration_seconds=time.monotonic() - started,
        )
        raise VoiceTranscriptionError(_diagnose_empty_response(response))
    if len(text) > MAX_TRANSCRIPT_CHARS:
        metric_sink.ai_call(
            "google",
            model_name,
            succeeded=False,
            duration_seconds=time.monotonic() - started,
        )
        raise VoiceTranscriptionError("The transcript exceeds the supported size.")

    metric_sink.ai_call(
        "google",
        model_name,
        succeeded=True,
        duration_seconds=time.monotonic() - started,
    )

    return text


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _extract_text(response: Any) -> str:
    """
    Safely pull transcribed text out of a Gemini response.

    response.text is a convenience accessor that raises ValueError when
    the first candidate has no parts (empty response) instead of
    returning ""; this walks the raw candidates/parts structure instead
    so a truly-empty response can be diagnosed rather than crashing.
    """
    try:
        return (response.text or "").strip()
    except Exception:
        pass

    for candidate in getattr(response, "candidates", None) or []:
        content = getattr(candidate, "content", None)
        parts = getattr(content, "parts", None) if content else None
        if not parts:
            continue
        joined = "".join(getattr(part, "text", "") or "" for part in parts).strip()
        if joined:
            return joined
    return ""


def _diagnose_empty_response(response: Any) -> str:
    """Turn an empty response into a stable, provider-neutral message."""
    feedback = getattr(response, "prompt_feedback", None)
    block_reason = getattr(feedback, "block_reason", None)
    if block_reason:
        return "The recording could not be transcribed. Please record it again."

    candidates = getattr(response, "candidates", None) or []
    if candidates:
        finish_reason = getattr(candidates[0], "finish_reason", None)
        # 1 / "STOP" is a normal completion; anything else explains the gap.
        if finish_reason not in (None, 1, "STOP"):
            return (
                "The recording ended without a transcript. Check the audio "
                "format and try recording again."
            )

    return (
        "No clear speech was detected in that recording — it may be "
        "silent, too quiet, or too short. Please try recording again, a bit "
        "closer to the mic."
    )
