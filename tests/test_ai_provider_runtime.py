"""Regression tests for Gemini fallback and safe runtime diagnostics."""

from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

from ai_provider import is_fallback_eligible_provider_error, provider_status_code
from backend_errors import ExternalProviderFailure
from planner import DayPlanOutput, PlannerEngine
from recommendation import RecommendationEngine, RecommendationOutput


class _ProviderError(Exception):
    def __init__(self, status: int) -> None:
        super().__init__("provider detail that must never be logged")
        self.status_code = status


class AiProviderClassificationTests(unittest.TestCase):
    def test_transient_and_missing_model_failures_are_fallback_eligible(self) -> None:
        for status in (404, 408, 429, 500, 502, 503, 504):
            with self.subTest(status=status):
                self.assertTrue(is_fallback_eligible_provider_error(_ProviderError(status)))
        self.assertFalse(is_fallback_eligible_provider_error(_ProviderError(400)))
        self.assertFalse(is_fallback_eligible_provider_error(_ProviderError(401)))

    def test_status_is_found_on_wrapped_exception(self) -> None:
        try:
            try:
                raise _ProviderError(429)
            except _ProviderError as inner:
                raise RuntimeError("wrapper") from inner
        except RuntimeError as outer:
            self.assertEqual(429, provider_status_code(outer))


class PlannerFallbackTests(unittest.TestCase):
    @staticmethod
    def _engine_with(primary_error: Exception):
        primary = MagicMock()
        primary.invoke.side_effect = primary_error
        fallback = MagicMock()
        fallback.invoke.return_value = DayPlanOutput.model_validate(
            {
                "planning_notes": "Fallback plan",
                "tasks": [
                    {
                        "title": "Study",
                        "description": "",
                        "category_name": "Learning",
                        "is_new_category": False,
                        "suggested_category_color": None,
                        "priority": 3,
                        "estimated_minutes": 30,
                        "is_fixed_time": False,
                        "fixed_start": None,
                        "needs_review": False,
                        "review_reason": None,
                    }
                ],
            }
        )
        engine = PlannerEngine()
        engine._parser = MagicMock()
        engine._parser.get_format_instructions.return_value = "json"
        return engine, primary, fallback

    def test_missing_primary_model_uses_fallback(self) -> None:
        engine, primary, fallback = self._engine_with(_ProviderError(404))
        with patch.object(engine, "_build_chain", side_effect=[primary, fallback]):
            result = engine.plan_day("Study for 30 minutes")
        self.assertEqual("Study", result.tasks[0].title)

    def test_malformed_primary_json_uses_fallback(self) -> None:
        class OutputParserException(Exception):
            pass

        engine, primary, fallback = self._engine_with(OutputParserException("bad json"))
        with patch.object(engine, "_build_chain", side_effect=[primary, fallback]):
            result = engine.plan_day("Study for 30 minutes")
        self.assertEqual("Study", result.tasks[0].title)

    def test_failure_log_excludes_provider_message(self) -> None:
        engine, primary, _ = self._engine_with(_ProviderError(400))
        with (
            patch.object(engine, "_build_chain", return_value=primary),
            self.assertLogs("coachai.ai", level="WARNING") as logs,
            self.assertRaises(ExternalProviderFailure),
        ):
            engine.plan_day("Study for 30 minutes")
        joined = "\n".join(logs.output)
        self.assertIn("status=400", joined)
        self.assertNotIn("provider detail", joined)


class RecommendationFallbackTests(unittest.TestCase):
    def test_transient_primary_failure_uses_fallback(self) -> None:
        primary = MagicMock()
        primary.invoke.side_effect = _ProviderError(503)
        fallback = MagicMock()
        fallback.invoke.return_value = RecommendationOutput(
            summary="ok", strengths=[], weaknesses=[], recommendations=[]
        )
        engine = RecommendationEngine()
        engine._parser = MagicMock()
        engine._parser.get_format_instructions.return_value = "json"
        with patch.object(engine, "_build_chain", side_effect=[primary, fallback]) as build:
            result = engine.analyze_schedule("09:00 work", "No history")
        self.assertEqual("ok", result.summary)
        self.assertEqual(engine._fallback_model_name, build.call_args_list[1].args[0])


if __name__ == "__main__":
    unittest.main()
