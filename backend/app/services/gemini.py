import logging
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import TypeVar

from app.core.config import get_settings
from app.schemas.ai import ChatTurn, PlanDraftResponse


logger = logging.getLogger("zenmaestro.gemini")
ResultT = TypeVar("ResultT")


class AiNotConfiguredError(RuntimeError):
    pass


class AiProviderError(RuntimeError):
    pass


@dataclass(frozen=True)
class ChatAttachment:
    filename: str
    mime_type: str
    data: bytes


COACH_SYSTEM_PROMPT = """You are Zen, the AI learning coach inside ZenMaestro.
Help the learner plan study, stay focused, reflect, and build consistency.
Be practical, warm, concise, and honest. Never invent facts about the learner.
Only use information present in the chat request. Do not claim access to their
profile, tasks, plan, progress, or history. Reply in the same language as the
learner's latest message. Keep the answer focused on learning and planning."""


PLANNER_SYSTEM_PROMPT = """You are ZenMaestro's learning-plan assistant.
Turn the learner's own free-form input into clear, individually actionable
learning tasks. Never invent goals or subjects that were not stated or clearly
implied. Reuse a supplied category when it fits. Use priority 1 for highest and
5 for lowest. Estimate realistic durations. Write the summary and task titles
in the same language as the input. Return only the requested structured data."""


class GeminiService:
    def __init__(self) -> None:
        settings = get_settings()
        self._api_key = (
            settings.gemini_api_key.get_secret_value()
            if settings.gemini_api_key is not None
            else None
        )
        self.model = settings.gemini_model
        fallbacks = (
            item.strip()
            for item in settings.gemini_fallback_models.split(",")
        )
        self.models = tuple(
            dict.fromkeys(
                model
                for model in (self.model, *fallbacks)
                if model
            )
        )

    @property
    def configured(self) -> bool:
        return bool(self._api_key)

    def _require_key(self) -> str:
        if not self._api_key:
            raise AiNotConfiguredError("Gemini is not configured.")
        return self._api_key

    def _with_model_fallback(
        self,
        request: Callable[[str], ResultT],
    ) -> ResultT:
        last_error: Exception | None = None
        for model in self.models:
            try:
                return request(model)
            except Exception as error:
                last_error = error
                status_code = getattr(error, "code", None)
                if status_code not in {404, 429, 500, 502, 503, 504}:
                    raise
                logger.warning(
                    "Gemini model %s is temporarily unavailable (%s); trying fallback.",
                    model,
                    status_code,
                )
        if last_error is not None:
            raise last_error
        raise AiProviderError("No Gemini model is configured.")

    def chat(
        self,
        message: str,
        history: Sequence[ChatTurn],
        attachment: ChatAttachment | None = None,
    ) -> str:
        api_key = self._require_key()
        try:
            from google import genai
            from google.genai import types

            contents = [
                types.Content(
                    role=turn.role,
                    parts=[types.Part.from_text(text=turn.content)],
                )
                for turn in history
            ]
            user_parts = []
            if attachment is not None:
                if attachment.mime_type.startswith("text/") or attachment.mime_type == "application/json":
                    try:
                        attachment_text = attachment.data.decode("utf-8")
                    except UnicodeDecodeError as error:
                        raise AiProviderError("The text attachment must use UTF-8 encoding.") from error
                    user_parts.append(
                        types.Part.from_text(
                            text=f"Selected file ({attachment.filename}):\n{attachment_text}"
                        )
                    )
                else:
                    user_parts.append(
                        types.Part.from_bytes(
                            data=attachment.data,
                            mime_type=attachment.mime_type,
                        )
                    )
                    user_parts.append(
                        types.Part.from_text(text=f"Selected file name: {attachment.filename}")
                    )
            user_parts.append(types.Part.from_text(text=message))
            contents.append(types.Content(role="user", parts=user_parts))
            with genai.Client(api_key=api_key) as client:
                response = self._with_model_fallback(
                    lambda model: client.models.generate_content(
                        model=model,
                        contents=contents,
                        config=types.GenerateContentConfig(
                            system_instruction=COACH_SYSTEM_PROMPT,
                            temperature=0.35,
                            max_output_tokens=900,
                        ),
                    )
                )
            reply = (response.text or "").strip()
            if not reply:
                raise AiProviderError("Gemini returned an empty response.")
            return reply
        except AiProviderError:
            raise
        except Exception as error:
            logger.exception("Gemini chat request failed: %s", type(error).__name__)
            raise AiProviderError("Gemini request failed.") from error

    def draft_plan(
        self,
        raw_input: str,
        existing_categories: Sequence[str],
    ) -> PlanDraftResponse:
        api_key = self._require_key()
        categories = ", ".join(existing_categories) or "None"
        prompt = (
            f"Existing categories supplied by the learner: {categories}\n\n"
            f"Learner input:\n{raw_input}"
        )
        try:
            from google import genai
            from google.genai import types

            with genai.Client(api_key=api_key) as client:
                response = self._with_model_fallback(
                    lambda model: client.models.generate_content(
                        model=model,
                        contents=prompt,
                        config=types.GenerateContentConfig(
                            system_instruction=PLANNER_SYSTEM_PROMPT,
                            temperature=0.2,
                            max_output_tokens=1800,
                            response_mime_type="application/json",
                            response_schema=PlanDraftResponse,
                        ),
                    )
                )
            if response.parsed is None:
                raise AiProviderError("Gemini returned no structured plan.")
            return PlanDraftResponse.model_validate(response.parsed)
        except AiProviderError:
            raise
        except Exception as error:
            logger.exception("Gemini planner request failed: %s", type(error).__name__)
            raise AiProviderError("Gemini request failed.") from error


def get_gemini_service() -> GeminiService:
    return GeminiService()
