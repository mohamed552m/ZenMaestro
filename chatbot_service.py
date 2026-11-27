"""
CoachAI – Chatbot Service (CoachAI Assistant)
================================================

Self-contained module for the CoachAI Assistant chatbot.
Uses Google Gemini with function calling to provide
data-grounded productivity coaching.

Reuses existing CoachAI services and analytics — does NOT
duplicate any backend logic or create new database structures.
"""

from __future__ import annotations

import json
import logging
import os
import random
import re
import subprocess
import sys
import time
from datetime import date, timedelta
from pathlib import Path
from typing import Any, Optional

from text_matching import normalize_for_matching
from rate_limiter import DatabaseRateLimiter, RateLimitExceeded
from backend_errors import ConfigurationError
from domain_validation import MAX_AI_INPUT_CHARS, MAX_AI_OUTPUT_TOKENS

# ─────────────────────────────────────────────────────────────
# Logging
# ─────────────────────────────────────────────────────────────
# Logs bounded failure categories and exception class names only. Provider
# messages and tracebacks are deliberately excluded because they may contain
# request data, account metadata, or credentials.
logger = logging.getLogger("coachai.chatbot_service")
if not logger.handlers:
    _handler = logging.StreamHandler()
    _handler.setFormatter(
        logging.Formatter("[%(asctime)s] %(levelname)s %(name)s: %(message)s")
    )
    logger.addHandler(_handler)
logger.setLevel(logging.INFO)


# ─────────────────────────────────────────────────────────────
# Constants
# ─────────────────────────────────────────────────────────────

_MODEL_NAME: str = "gemini-3.1-flash-lite"
_FALLBACK_MODEL_NAME: str = "gemini-3.5-flash"


def _configured_model(env_name: str, default: str) -> str:
    """Return one bounded Gemini model identifier from the environment."""
    value = os.getenv(env_name, default).strip()
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", value):
        raise ConfigurationError(f"{env_name} is invalid.")
    return value


def _is_transient_provider_error(exc: Exception) -> bool:
    """Use the shared provider classifier for chat fallback decisions."""
    from ai_provider import is_fallback_eligible_provider_error

    return is_fallback_eligible_provider_error(exc)

_SYSTEM_PROMPT: str = """\
You are **CoachAI Assistant**, an AI productivity coach built into the CoachAI application.

## Your Role
You help users understand and improve their productivity by analyzing their **real CoachAI data** — tasks, schedules, completion rates, productivity scores, and behavioral patterns.

## Critical Rules — Data Accuracy

1. **NEVER invent user-specific numbers, statistics, tasks, or history.** Every user-specific claim must come from the data retrieved by your tools.
2. **NEVER fabricate analytics results.** If a tool returns data, use it. If no tool has been called or the data is missing, say so.
3. **NEVER present guesses as facts.** If you don't have data, explicitly state: "I don't have enough data in CoachAI to answer that confidently."
4. **NEVER claim a user has a habit or pattern unless the retrieved data supports it.**
5. **NEVER invent previous tasks or task history.**

## Response Structure

Always distinguish between three types of statements:

- **Data-based fact**: Directly from CoachAI data. Example: "According to your data, you completed 78% of your planned tasks in the last 14 days."
- **Analysis**: Your interpretation of the data. Example: "This suggests your planned workload may be slightly higher than your usual completion capacity."
- **Recommendation**: Your advice based on the analysis. Example: "I'd recommend limiting today to three main tasks."

Never present a recommendation as if it were a database fact.

## What-If Questions

When users ask hypothetical questions like "Should I study for two hours now?" or "Should I postpone this task?":
1. Retrieve relevant data (today's tasks, productivity by hour, similar past tasks, workload, capacity).
2. Analyze the data against the hypothetical scenario.
3. Provide an evidence-based recommendation.
4. If data is insufficient, say so clearly before offering general advice.

## Insufficient Data

When data is insufficient to answer confidently:
1. Say: "I don't have enough data in CoachAI to answer that confidently."
2. Optionally provide general productivity advice, but **clearly label it as general advice**, not user-specific analysis.
3. Suggest actions to generate the needed data (e.g., "Try tracking your study sessions so CoachAI can identify your most productive hours.").

## Scope

Your expertise covers: productivity, tasks, planning, scheduling, time management, personal performance, and productivity analytics.

If the user asks about something completely unrelated (politics, entertainment, general trivia, etc.):
- Do NOT answer the unrelated question.
- Politely redirect: "I'm CoachAI Assistant, and my main focus is helping you with productivity, planning, and performance. If you have a question about your tasks, schedule, or productivity, I'm here to help!"

## Casual & General Questions

Not every message needs the full data-retrieval-and-analysis treatment. Match your response to what's actually being asked:

- **Small talk** (greetings, thanks, how-are-you, goodbyes): respond briefly and warmly. No tools, no fact/analysis/recommendation structure — a short natural reply is the whole answer.
- **General, non-personal questions** (e.g. "what does burnout risk mean?", "what's a good way to prioritize tasks?", "how many hours of deep work is realistic?"): answer directly and concisely from general knowledge. If your answer could be mistaken for something personalized, clearly label it as general advice. Don't call tools unless the user is also asking about their own data.
- **Personal data questions and what-if scenarios**: this is where the full grounded approach applies — call the relevant tools, then use the fact/analysis/recommendation structure from above.

Only reach for the heavier structure when the question genuinely calls for it. A one-line answer to a one-line question is not a failure to be thorough — it's the right amount of help.

### Examples

User: "hey, what's up?"
Assistant: "Hey! Doing well and ready to help — want me to check your tasks or progress for today?"

User: "what does burnout risk mean?"
Assistant: "Burnout risk is a general measure of whether your recent workload and completion patterns suggest you might be overextending yourself — things like consistently high planned time paired with low completion rates. (This is a general explanation, not your specific number — ask me to check your current burnout risk if you'd like that!)"

User: "should I study for 2 more hours right now?"
Assistant: [calls get_daily_analysis and get_productivity_by_hour, then answers using the fact/analysis/recommendation structure, grounded in what those tools returned]

## Personality

- Friendly, supportive, and professional
- Practical and concise
- Honest — do NOT blindly agree with the user if their plan looks unrealistic based on data
- Motivating without exaggeration
- Use specific numbers and data when available

## Tool Usage

You have access to tools that retrieve the user's actual CoachAI data. **Always call relevant tools before making user-specific claims.** Do not answer productivity questions from memory alone — use the tools to get current data first.

When multiple tools are relevant, call them all to get a comprehensive picture.

## Language

Always reply in the SAME language the user just wrote in. If they write in Arabic, your entire reply must be Arabic — do not mix in English words or technical terms. Use this glossary and do NOT leave these terms in English or add English in parentheses next to them:

- burnout risk → خطر الإرهاق
- streak / current streak / longest streak → سلسلة الإنجاز
- completion rate → نسبة الإنجاز
- productivity score → مؤشر الإنتاجية
- consistency score → مؤشر الانتظام
- planning accuracy → دقة التخطيط
"""


# ─────────────────────────────────────────────────────────────
# Casual Message Shortcut (no API call at all)
#
# Bare greetings/thanks/farewells never need Gemini, tools, or the
# fact/analysis/recommendation structure — matching them here and
# replying instantly is both cheaper (no API round-trip) and more
# naturally conversational than routing them through the full model.
#
# Deliberately conservative: every pattern anchors on the WHOLE
# trimmed message (^...$), not a substring search, so a message that
# merely starts with a greeting but goes on to ask something real
# (e.g. "hey, what's my best category?") is never short-circuited —
# only messages that are ENTIRELY a greeting/thanks/farewell are.
# The system prompt's "Casual & General Questions" section is the
# backstop for anything phrased slightly differently that slips past
# these patterns (e.g. "hey, how's it going today?").
# ─────────────────────────────────────────────────────────────

_GREETING_PATTERN = re.compile(
    r"^\s*(hi+|hello+|hey+|hiya|yo|sup|good\s*(morning|evening|afternoon)|"
    r"مرحبا|اهلا|أهلا|السلام عليكم|هاي|هلا|ازيك|إزيك)\s*[!.,؟?]*\s*$",
    re.IGNORECASE,
)
_THANKS_PATTERN = re.compile(
    r"^\s*(thanks?( you)?( so much| a lot)?|thx|ty|"
    r"شكرا|شكرًا|تسلم|متشكر|مشكور|الله يخليك)\s*[!.,؟?]*\s*$",
    re.IGNORECASE,
)
_FAREWELL_PATTERN = re.compile(
    r"^\s*(bye|goodbye|see\s*you|later|take\s*care|"
    r"مع السلامة|باي|سلام)\s*[!.,؟?]*\s*$",
    re.IGNORECASE,
)

_ARABIC_CHARS_PATTERN = re.compile(r"[\u0600-\u06FF]")


def _is_arabic(text: str) -> bool:
    """True if the text contains any Arabic script characters."""
    return bool(_ARABIC_CHARS_PATTERN.search(text))


_GREETING_REPLIES_EN: list[str] = [
    "Hey! How's your day going so far? Ask me anything about your tasks or productivity.",
    "Hi there! Ready to dig into your schedule or progress whenever you are.",
    "Hello! What would you like to know about your productivity today?",
]
_GREETING_REPLIES_AR: list[str] = [
    "أهلاً! عامل إيه النهاردة؟ اسألني عن أي حاجة في مهامك أو إنتاجيتك.",
    "هاي! جاهز أساعدك في جدولك أو تقدمك وقت ما تحب.",
    "أهلاً بيك! عايز تعرف إيه عن إنتاجيتك النهاردة؟",
]
_THANKS_REPLIES_EN: list[str] = [
    "You're welcome! Let me know if you want to dig into anything else.",
    "Anytime! Happy to help with your planning.",
    "Glad I could help. 🙂",
]
_THANKS_REPLIES_AR: list[str] = [
    "العفو! قولي لو عايز نتكلم عن أي حاجة تانية.",
    "في أي وقت! سعيد إني ساعدتك في التخطيط.",
    "الحمد لله إني قدرت أساعد. 🙂",
]
_FAREWELL_REPLIES_EN: list[str] = [
    "See you later — good luck with your tasks today!",
    "Take care! I'll be here whenever you need me.",
    "Bye for now — go get that to-do list done. 💪",
]
_FAREWELL_REPLIES_AR: list[str] = [
    "أشوفك بعدين — بالتوفيق في مهامك النهاردة!",
    "خد بالك من نفسك! هكون هنا وقت ما تحتاجني.",
    "مع السلامة — يلا خلص الليستة بتاعتك. 💪",
]


def _try_casual_shortcut(user_message: str) -> Optional[str]:
    """
    Return an instant, canned reply for a bare greeting/thanks/farewell,
    or None if the message needs the real model (which is the case for
    almost everything — this only ever matches whole, standalone
    small-talk messages, never a question of any kind).

    The reply language matches the language the user actually wrote in,
    not a random choice — Arabic input always gets an Arabic canned
    reply, never an English one and vice versa.

    Args:
        user_message: The raw message as typed by the user.

    Returns:
        A short friendly reply, or None to fall through to Gemini.
    """
    text = user_message.strip()
    if not text:
        return None
    arabic = _is_arabic(text)
    if _GREETING_PATTERN.match(text):
        return random.choice(_GREETING_REPLIES_AR if arabic else _GREETING_REPLIES_EN)
    if _THANKS_PATTERN.match(text):
        return random.choice(_THANKS_REPLIES_AR if arabic else _THANKS_REPLIES_EN)
    if _FAREWELL_PATTERN.match(text):
        return random.choice(_FAREWELL_REPLIES_AR if arabic else _FAREWELL_REPLIES_EN)
    return None


# ─────────────────────────────────────────────────────────────
# API Key
# ─────────────────────────────────────────────────────────────

def _get_api_key() -> Optional[str]:
    """Retrieve the API key from environment, reusing the existing config."""
    return os.getenv("GOOGLE_API_KEY") or os.getenv("GEMINI_API_KEY")


def _ai_timeout_seconds() -> float:
    try:
        value = float(os.getenv("COACHAI_AI_TIMEOUT_SECONDS", "35"))
    except ValueError as exc:
        raise ConfigurationError("COACHAI_AI_TIMEOUT_SECONDS must be numeric.") from exc
    if not 0.1 <= value <= 120:
        raise ConfigurationError(
            "COACHAI_AI_TIMEOUT_SECONDS must be between 0.1 and 120 seconds."
        )
    return value


def _chat_tool_round_limit() -> int:
    """Keep interactive chat responsive while still allowing grounded tools."""
    try:
        value = int(os.getenv("COACHAI_CHAT_TOOL_ROUNDS", "2"))
    except ValueError as exc:
        raise ConfigurationError("COACHAI_CHAT_TOOL_ROUNDS must be an integer.") from exc
    if not 1 <= value <= 3:
        raise ConfigurationError("COACHAI_CHAT_TOOL_ROUNDS must be between 1 and 3.")
    return value


def _chat_output_tokens() -> int:
    try:
        value = int(os.getenv("COACHAI_CHAT_OUTPUT_TOKENS", "1024"))
    except ValueError as exc:
        raise ConfigurationError("COACHAI_CHAT_OUTPUT_TOKENS must be an integer.") from exc
    if not 256 <= value <= 4096:
        raise ConfigurationError("COACHAI_CHAT_OUTPUT_TOKENS must be between 256 and 4096.")
    return value


def _chat_process_timeout_seconds() -> float:
    """Bound the whole provider conversation, including tool rounds."""
    try:
        value = float(os.getenv("COACHAI_CHAT_TOTAL_TIMEOUT_SECONDS", "80"))
    except ValueError as exc:
        raise ConfigurationError(
            "COACHAI_CHAT_TOTAL_TIMEOUT_SECONDS must be numeric."
        ) from exc
    if not 5 <= value <= 80:
        raise ConfigurationError(
            "COACHAI_CHAT_TOTAL_TIMEOUT_SECONDS must be between 5 and 80 seconds."
        )
    return value


def _resolve_database(db=None):
    """Resolve the legacy UI database only when no explicit core DB is supplied."""
    if db is not None:
        return db
    from services import get_database

    return get_database()


def _build_analytics_profile(user_id: str, db=None):
    if db is None:
        from services import load_analytics_profile
        from config import DEFAULT_ANALYTICS_WINDOW

        return load_analytics_profile(
            user_id=user_id, window_days=DEFAULT_ANALYTICS_WINDOW,
        )
    from analytics import AnalyticsEngine
    from config import DEFAULT_ANALYTICS_WINDOW

    return AnalyticsEngine(db).build_profile(
        user_id=user_id,
        window_days=DEFAULT_ANALYTICS_WINDOW,
        language="en",
    )


# ─────────────────────────────────────────────────────────────
# Tool Declarations (Gemini Function Calling)
# ─────────────────────────────────────────────────────────────

def _build_tool_declarations():
    """Build Gemini function declarations for the chatbot data tools."""
    from google.genai import types

    return types.Tool(
        function_declarations=[
            types.FunctionDeclaration(
                name="get_today_tasks",
                description=(
                    "Get today's planned tasks for the user. Returns task titles, "
                    "priorities, statuses (pending/in_progress/completed/failed), "
                    "scheduled start/end times, estimated durations, and categories. "
                    "Call this when the user asks about today's tasks, schedule, plan, "
                    "or current workload."
                ),
            ),
            types.FunctionDeclaration(
                name="get_task_history",
                description=(
                    "Get the user's task history over recent days. Returns completed "
                    "and failed tasks with titles, statuses, actual vs estimated "
                    "durations, priorities, failure reasons, categories, and dates. "
                    "Call this when the user asks about past performance, previous "
                    "tasks, or what they did recently."
                ),
                parameters={
                    "type": "OBJECT",
                    "properties": {
                        "days": {
                            "type": "INTEGER",
                            "description": (
                                "Number of days to look back. Default 14, max 90."
                            ),
                        },
                    },
                },
            ),
            types.FunctionDeclaration(
                name="get_productivity_summary",
                description=(
                    "Get the user's overall productivity analytics summary. Returns "
                    "productivity score (0-100), completion rate, failure rate, "
                    "planning accuracy, consistency score, current/longest streaks, "
                    "burnout risk, trend direction, total tasks/completed/failed, "
                    "and confidence level. Call this for general productivity questions."
                ),
            ),
            types.FunctionDeclaration(
                name="get_productivity_by_hour",
                description=(
                    "Get the user's productivity patterns by time of day. Returns "
                    "the best productivity hour and completion rate at that hour. "
                    "Call this when the user asks about their best time to work, "
                    "most productive hours, or when to schedule tasks."
                ),
            ),
            types.FunctionDeclaration(
                name="get_completion_rate",
                description=(
                    "Get the user's task completion and failure rates with detailed "
                    "breakdown. Returns completion rate, failure rate, total tasks "
                    "completed vs failed, and top failure reasons. Call this when "
                    "the user asks about their success rate or why tasks fail."
                ),
            ),
            types.FunctionDeclaration(
                name="get_weekly_analysis",
                description=(
                    "Get the user's weekly productivity analysis. Returns trend "
                    "direction (improving/declining/stable), consistency score, "
                    "active days, streaks, detected patterns, and insights. Call "
                    "this when the user asks about weekly trends, consistency, or "
                    "progress over time."
                ),
            ),
            types.FunctionDeclaration(
                name="get_daily_analysis",
                description=(
                    "Get analysis of today's workload vs the user's historical "
                    "capacity. Returns today's planned minutes, recommended daily "
                    "minutes based on history, whether the day is overloaded, "
                    "current task count, and completion status. Call this when the "
                    "user asks if their day is realistic, if they should add more "
                    "tasks, or about their current workload."
                ),
            ),
            types.FunctionDeclaration(
                name="get_similar_tasks",
                description=(
                    "Search the user's task history for tasks similar to a given "
                    "keyword. Returns matching tasks with their actual durations, "
                    "statuses, and when they were done. Call this when the user asks "
                    "how long a specific type of task usually takes, or about their "
                    "experience with similar tasks."
                ),
                parameters={
                    "type": "OBJECT",
                    "properties": {
                        "task_keyword": {
                            "type": "STRING",
                            "description": (
                                "Keyword to search for in task titles "
                                "(e.g., 'study', 'exercise', 'coding')."
                            ),
                        },
                    },
                    "required": ["task_keyword"],
                },
            ),
        ]
    )


# ─────────────────────────────────────────────────────────────
# Tool Implementations (reusing existing CoachAI services)
# ─────────────────────────────────────────────────────────────

def _impl_get_today_tasks(user_id: str, db=None) -> dict:
    """Retrieve today's tasks using existing services."""
    if db is None:
        # Keep the historical UI adapter for direct legacy calls and tests.
        from services import load_today_tasks, load_today_plan, load_categories

        plan = load_today_plan(user_id=user_id)
        tasks = load_today_tasks(user_id=user_id) if plan is not None else []
        categories = load_categories(user_id=user_id) if plan is not None else []
    else:
        row = db.get_today_plan(user_id)
        plan = dict(row) if row is not None else None
        tasks = (
            db.get_tasks_with_segments(int(plan["plan_id"]), user_id)
            if plan is not None else []
        )
        categories = [dict(row) for row in db.get_categories(user_id)]
    if plan is None:
        return {"has_plan": False, "message": "No plan created for today."}

    if not tasks:
        return {
            "has_plan": True,
            "plan_date": str(plan.get("plan_date", "")),
            "tasks": [],
            "message": "Today's plan exists but has no tasks yet.",
        }

    # Build category lookup for readable names
    cat_map = {c["category_id"]: c["name"] for c in categories}

    task_list = []
    for t in tasks:
        cat_id = t.get("category_id")
        task_list.append({
            "title": t.get("title", "Untitled"),
            "priority": t.get("priority", 3),
            "status": t.get("status", "pending"),
            "estimated_minutes": t.get("estimated_minutes", 0),
            "scheduled_start": t.get("scheduled_start"),
            "scheduled_end": t.get("scheduled_end"),
            "segments": [
                {"date": s.get("effective_date") or s.get("scheduled_date") or str(plan.get("plan_date", "")),
                 "start": s.get("scheduled_start"), "end": s.get("scheduled_end")}
                for s in (t.get("segments") or [])
            ],
            "description": t.get("description", ""),
            "category": cat_map.get(cat_id) if cat_id else None,
            "failure_reason": t.get("failure_reason"),
            "actual_minutes": t.get("actual_minutes"),
        })

    total_planned = sum(t["estimated_minutes"] for t in task_list)
    completed = sum(1 for t in task_list if t["status"] == "completed")
    pending = sum(1 for t in task_list if t["status"] == "pending")
    in_progress = sum(1 for t in task_list if t["status"] == "in_progress")
    failed = sum(1 for t in task_list if t["status"] == "failed")

    return {
        "has_plan": True,
        "plan_date": str(plan.get("plan_date", "")),
        "total_tasks": len(task_list),
        "total_planned_minutes": total_planned,
        "completed": completed,
        "pending": pending,
        "in_progress": in_progress,
        "failed": failed,
        "tasks": task_list,
    }


def _impl_get_task_history(user_id: str, days: int = 14, db=None) -> dict:
    """Retrieve task history using existing database functions."""
    from timezone_utils import user_today

    days = max(1, min(days, 90))
    db = _resolve_database(db)
    since_date = user_today(db, user_id) - timedelta(days=days)

    rows = db.get_recent_tasks_for_user(user_id=user_id, since_date=since_date)

    if not rows:
        return {
            "days_searched": days,
            "total_tasks": 0,
            "message": f"No task history found in the last {days} days.",
        }

    tasks = []
    for r in rows:
        tasks.append({
            "title": r["title"],
            "status": r["status"],
            "priority": r["priority"],
            "estimated_minutes": r["estimated_minutes"],
            "actual_minutes": r["actual_minutes"],
            "failure_reason": r["failure_reason"],
            "plan_date": str(r["plan_date"]),
            "category": r["category_name"] if "category_name" in r.keys() else None,
        })

    completed = sum(1 for t in tasks if t["status"] == "completed")
    failed = sum(1 for t in tasks if t["status"] == "failed")
    total = len(tasks)

    return {
        "days_searched": days,
        "total_tasks": total,
        "completed": completed,
        "failed": failed,
        "pending": total - completed - failed,
        "completion_rate": round(completed / total, 3) if total > 0 else 0,
        "tasks": tasks[:50],  # Limit to avoid token overflow
    }


def _impl_get_productivity_summary(user_id: str, db=None) -> dict:
    """Retrieve productivity summary using existing analytics engine."""
    try:
        profile = _build_analytics_profile(user_id, db)
    except Exception:
        return {"error": "Could not load analytics. There may not be enough data yet."}

    return {
        "productivity_score": round(profile.productivity.score, 1),
        "completion_rate": round(profile.completion_rate, 3),
        "failure_rate": round(profile.failure_rate, 3),
        "total_tasks": profile.total_tasks,
        "total_completed": profile.total_completed,
        "total_failed": profile.total_failed,
        "planning_accuracy": round(profile.planning.planning_accuracy, 3),
        "planning_bias": profile.planning.bias_direction,
        "consistency_score": round(profile.consistency.consistency_score, 3),
        "current_streak": profile.consistency.current_streak,
        "longest_streak": profile.consistency.longest_streak,
        "active_days": profile.consistency.active_days,
        "burnout_risk": round(profile.burnout.burnout_risk, 1),
        "trend_direction": profile.trend.trend_direction,
        "trend_score": round(profile.trend.trend_score, 3),
        "confidence_level": profile.overall_confidence.level,
        "sample_size": profile.sample_size,
        "window_days": profile.window_days,
    }


def _impl_get_productivity_by_hour(user_id: str, db=None) -> dict:
    """Retrieve best productivity hour from existing analytics."""
    try:
        profile = _build_analytics_profile(user_id, db)
    except Exception:
        return {"error": "Could not load analytics. There may not be enough data yet."}

    best_hour = profile.best_hour
    if best_hour.best_hour is None:
        return {
            "has_data": False,
            "message": "Not enough data to determine the best productivity hour yet.",
        }

    return {
        "has_data": True,
        "best_hour": best_hour.best_hour,
        "best_hour_formatted": f"{best_hour.best_hour}:00",
        "completion_rate_at_best": round(best_hour.completion_rate_at_best, 3),
    }


def _impl_get_completion_rate(user_id: str, db=None) -> dict:
    """Retrieve detailed completion/failure breakdown."""
    from config import DEFAULT_ANALYTICS_WINDOW

    try:
        profile = _build_analytics_profile(user_id, db)
    except Exception:
        return {"error": "Could not load analytics."}

    # Get failure reason distribution
    db = _resolve_database(db)
    from timezone_utils import user_today
    since_date = user_today(db, user_id) - timedelta(days=DEFAULT_ANALYTICS_WINDOW)
    failure_rows = db.get_failure_reason_counts(
        user_id=user_id, since_date=since_date,
    )
    failure_reasons = [
        {"reason": r["failure_reason"], "count": r["occurrence_count"]}
        for r in failure_rows
        if r["failure_reason"]
    ]

    return {
        "completion_rate": round(profile.completion_rate, 3),
        "failure_rate": round(profile.failure_rate, 3),
        "total_completed": profile.total_completed,
        "total_failed": profile.total_failed,
        "total_tasks": profile.total_tasks,
        "top_failure_reasons": failure_reasons,
        "window_days": profile.window_days,
    }


def _impl_get_weekly_analysis(user_id: str, db=None) -> dict:
    """Retrieve weekly trends and consistency data."""
    try:
        profile = _build_analytics_profile(user_id, db)
    except Exception:
        return {"error": "Could not load analytics."}

    result: dict[str, Any] = {
        "trend_direction": profile.trend.trend_direction,
        "trend_score": round(profile.trend.trend_score, 3),
        "consistency_score": round(profile.consistency.consistency_score, 3),
        "active_days": profile.consistency.active_days,
        "total_observation_days": profile.consistency.total_observation_days,
        "current_streak": profile.consistency.current_streak,
        "longest_streak": profile.consistency.longest_streak,
        "confidence_level": profile.overall_confidence.level,
        "window_days": profile.window_days,
    }

    # Add pattern observations if available
    if hasattr(profile, "patterns") and profile.patterns.patterns:
        result["detected_patterns"] = [
            {
                "name": p.pattern_name,
                "observation": p.observation,
                "confidence": p.confidence,
            }
            for p in profile.patterns.patterns[:5]
        ]

    # Add insights if available
    if hasattr(profile, "insights") and profile.insights.insights:
        result["insights"] = [
            {
                "observation": i.observation,
                "evidence": i.evidence,
            }
            for i in profile.insights.insights[:5]
        ]

    return result


def _impl_get_daily_analysis(user_id: str, db=None) -> dict:
    """Analyze today's workload vs historical capacity."""
    if db is None:
        from services import load_today_tasks

        tasks = load_today_tasks(user_id=user_id)
    else:
        plan = db.get_today_plan(user_id)
        tasks = (
            db.get_tasks_with_segments(int(plan["plan_id"]), user_id)
            if plan is not None else []
        )

    if not tasks:
        return {
            "has_plan": False,
            "message": "No tasks planned for today.",
        }

    total_estimated = sum(t.get("estimated_minutes", 0) for t in tasks)
    completed_count = sum(
        1 for t in tasks if t.get("status") == "completed"
    )
    pending_count = sum(
        1 for t in tasks if t.get("status") in ("pending", "in_progress")
    )
    completed_minutes = sum(
        t.get("actual_minutes") or t.get("estimated_minutes", 0)
        for t in tasks
        if t.get("status") == "completed"
    )
    remaining_minutes = sum(
        t.get("estimated_minutes", 0)
        for t in tasks
        if t.get("status") in ("pending", "in_progress")
    )

    result: dict[str, Any] = {
        "has_plan": True,
        "total_tasks": len(tasks),
        "completed_tasks": completed_count,
        "pending_tasks": pending_count,
        "total_estimated_minutes": total_estimated,
        "completed_minutes": completed_minutes,
        "remaining_minutes": remaining_minutes,
    }

    # Add capacity analysis from existing service
    try:
        if db is None:
            from services import check_capacity_for_today

            capacity = check_capacity_for_today(
                additional_minutes=0,
                user_id=user_id,
            )
        else:
            from config import (
                CAPACITY_MIN_CONFIDENCE_TO_WARN,
                CAPACITY_OVERLOAD_MARGIN,
            )

            profile = _build_analytics_profile(user_id, db)
            realistic = profile.capacity
            recommended = realistic.recommended_daily_minutes
            overload_fraction = (
                (total_estimated - recommended) / recommended
                if recommended > 0 else 0.0
            )
            capacity = {
                "recommended_minutes": recommended,
                "triggered": (
                    realistic.confidence.level in CAPACITY_MIN_CONFIDENCE_TO_WARN
                    and overload_fraction >= CAPACITY_OVERLOAD_MARGIN
                ),
                "overload_fraction": overload_fraction,
                "confidence": realistic.confidence.level,
            }
        result["recommended_daily_minutes"] = capacity["recommended_minutes"]
        result["is_overloaded"] = capacity["triggered"]
        result["overload_fraction"] = round(capacity["overload_fraction"], 3)
        result["capacity_confidence"] = capacity["confidence"]
    except Exception:
        result["capacity_note"] = "Could not compute capacity analysis."

    return result


def _impl_get_similar_tasks(user_id: str, task_keyword: str, db=None) -> dict:
    """Search task history for similar tasks by keyword."""
    if not task_keyword or not task_keyword.strip():
        return {"error": "Please provide a keyword to search for."}

    keyword = normalize_for_matching(task_keyword)
    db = _resolve_database(db)
    from timezone_utils import user_today
    since_date = user_today(db, user_id) - timedelta(days=90)

    rows = db.get_recent_tasks_for_user(
        user_id=user_id, since_date=since_date,
    )

    matching = []
    for r in rows:
        title = normalize_for_matching(r["title"] or "")
        desc = ""
        try:
            desc = normalize_for_matching(r["description"] or "")
        except (KeyError, TypeError):
            pass
        if keyword in title or keyword in desc:
            matching.append({
                "title": r["title"],
                "status": r["status"],
                "estimated_minutes": r["estimated_minutes"],
                "actual_minutes": r["actual_minutes"],
                "plan_date": str(r["plan_date"]),
                "priority": r["priority"],
                "failure_reason": r["failure_reason"],
            })

    if not matching:
        return {
            "keyword": task_keyword,
            "found": 0,
            "message": f"No tasks matching '{task_keyword}' found in the last 90 days.",
        }

    # Calculate stats on matching tasks
    completed = [t for t in matching if t["status"] == "completed"]
    actual_durations = [
        t["actual_minutes"]
        for t in completed
        if t["actual_minutes"] is not None
    ]
    avg_duration = (
        round(sum(actual_durations) / len(actual_durations), 1)
        if actual_durations
        else None
    )

    return {
        "keyword": task_keyword,
        "found": len(matching),
        "completed_count": len(completed),
        "failed_count": sum(1 for t in matching if t["status"] == "failed"),
        "average_actual_minutes": avg_duration,
        "tasks": matching[:20],  # Limit to 20 to avoid token overflow
    }


# ─────────────────────────────────────────────────────────────
# Tool Dispatcher
# ─────────────────────────────────────────────────────────────

_TOOL_DISPATCH: dict[str, Any] = {
    "get_today_tasks": lambda uid, args, db: _impl_get_today_tasks(uid, db),
    "get_task_history": lambda uid, args, db: _impl_get_task_history(
        uid, int(args.get("days", 14)), db,
    ),
    "get_productivity_summary": lambda uid, args, db: _impl_get_productivity_summary(uid, db),
    "get_productivity_by_hour": lambda uid, args, db: _impl_get_productivity_by_hour(uid, db),
    "get_completion_rate": lambda uid, args, db: _impl_get_completion_rate(uid, db),
    "get_weekly_analysis": lambda uid, args, db: _impl_get_weekly_analysis(uid, db),
    "get_daily_analysis": lambda uid, args, db: _impl_get_daily_analysis(uid, db),
    "get_similar_tasks": lambda uid, args, db: _impl_get_similar_tasks(
        uid, args.get("task_keyword", ""), db,
    ),
}


def _execute_tool(tool_name: str, tool_args: dict, user_id: str, *, db=None) -> str:
    """Execute a chatbot tool by name and return the result as a JSON string."""
    handler = _TOOL_DISPATCH.get(tool_name)
    if handler is None:
        return json.dumps({
            "error": "unknown_tool",
            "message": "The requested operation is not available.",
        })

    try:
        result = handler(user_id, tool_args, db)
        return json.dumps(result, default=str)
    except Exception as exc:
        logger.warning(
            "Chatbot tool execution failed | tool=%s error_type=%s",
            tool_name,
            type(exc).__name__,
        )
        return json.dumps({
            "error": "tool_execution_failed",
            "message": "Could not retrieve the requested data.",
        })


# ─────────────────────────────────────────────────────────────
# Error Classification Helper
# ─────────────────────────────────────────────────────────────

def _classify_and_log_error(exc: Exception, context: str) -> str:
    """
    Classify a provider failure and record only bounded diagnostics.

    Provider messages and tracebacks may contain request data, credentials,
    or account details, so neither is written to application logs.
    """
    error_str = str(exc).lower()
    error_type = type(exc).__name__

    if "permission" in error_type.lower() or "invalid" in error_str:
        category = "permission"
    elif "resourceexhausted" in error_type.lower() or "quota" in error_str:
        category = "quota"
    elif "blocked" in error_type.lower() or "blocked" in error_str:
        category = "blocked"
    else:
        category = "other"

    from ai_provider import provider_status_code

    status = provider_status_code(exc)
    logger.warning(
        "External AI request failed | context=%s error_type=%s status=%s category=%s",
        context,
        error_type,
        status,
        category,
    )
    return category


_ERROR_MESSAGES_EN = {
    "permission": (
        "⚠️ The AI service is not available right now. "
        "Please try again later."
    ),
    "quota": (
        "⚠️ The AI service is busy right now. "
        "Please wait a moment and try again."
    ),
    "blocked": (
        "I wasn't able to process that message. "
        "Could you try rephrasing your question?"
    ),
    "other": (
        "⚠️ I encountered a temporary issue with the AI service. "
        "Please try again in a moment."
    ),
}
_ERROR_MESSAGES_AR = {
    "permission": (
        "⚠️ خدمة الذكاء الاصطناعي غير متاحة حالياً. "
        "جرّب مرة أخرى لاحقاً."
    ),
    "quota": (
        "⚠️ خدمة الذكاء الاصطناعي مشغولة حالياً. "
        "استنى لحظة وجرب تاني."
    ),
    "blocked": (
        "معرفتش أعالج الرسالة دي. "
        "ممكن تجرب تعيد صياغة سؤالك؟"
    ),
    "other": (
        "⚠️ حصلت مشكلة مؤقتة في خدمة الذكاء الاصطناعي. "
        "جرب تاني بعد شوية."
    ),
}


# ─────────────────────────────────────────────────────────────
# Chatbot Service
# ─────────────────────────────────────────────────────────────

class ChatbotService:
    """
    Manages a Gemini chat session with function calling for the
    CoachAI Assistant. Each instance is tied to a specific user.
    """

    # Chat is naturally higher-frequency than the planner/coach calls,
    # but each message can still trigger multiple tool-calling round
    # trips to Gemini, so it still needs a ceiling against a rapid-fire
    # loop (accidental or scripted).
    def __init__(self, user_id: str, *, db=None) -> None:
        self.user_id = user_id
        self.db = _resolve_database(db)
        self._rate_limiter = DatabaseRateLimiter(
            self.db,
            scope="ai.chatbot",
            max_requests=15,
            window_seconds=60,
        )
        self._chat: Any = None
        self._client: Any = None
        self._max_tool_rounds = _chat_tool_round_limit()
        self._model_name = _configured_model("COACHAI_CHAT_MODEL", _MODEL_NAME)
        self._fallback_model_name = _configured_model(
            "COACHAI_CHAT_FALLBACK_MODEL",
            _FALLBACK_MODEL_NAME,
        )
        self._active_model_name = self._model_name

        api_key = _get_api_key()
        if not api_key:
            raise ValueError("The AI service is not configured.")

        self._init_chat(api_key)

    def _init_chat(self, api_key: str) -> None:
        """Initialize the Gemini client, model, and chat session."""
        from google import genai
        from google.genai import types

        self._client = genai.Client(
            api_key=api_key,
            http_options=types.HttpOptions(
                api_version="v1",
                timeout=max(100, int(_ai_timeout_seconds() * 1_000)),
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

        # Append current date (in the user's own timezone, not the
        # server's) to the system prompt for temporal context.
        from timezone_utils import user_today
        today_str = user_today(self.db, self.user_id).isoformat()
        system_prompt = (
            _SYSTEM_PROMPT + f"\n\nToday's date is {today_str}."
        )

        self._config = types.GenerateContentConfig(
            system_instruction=system_prompt,
            tools=[_build_tool_declarations()],
            temperature=0.2,
            max_output_tokens=_chat_output_tokens(),
        )

        self._chat = self._create_chat(self._model_name)

    def _create_chat(self, model_name: str) -> Any:
        return self._client.chats.create(
            model=model_name,
            config=self._config,
        )

    def _send_with_tools(self, user_message: str) -> str:
        response = self._send_provider_message(user_message, stage="initial")
        response = self._handle_function_calls(response)
        return self._extract_text(response)

    def _send_provider_message(self, payload: Any, *, stage: str) -> Any:
        """Call Gemini while recording bounded, content-free latency diagnostics."""
        logger.info(
            "Chat provider call started | stage=%s model=%s",
            stage,
            self._active_model_name,
        )
        started_at = time.monotonic()
        try:
            response = self._chat.send_message(payload)
        except Exception as exc:
            from ai_provider import provider_status_code

            logger.info(
                "Chat provider call | stage=%s model=%s outcome=error "
                "duration_ms=%d error_type=%s status=%s",
                stage,
                self._active_model_name,
                int((time.monotonic() - started_at) * 1000),
                type(exc).__name__,
                provider_status_code(exc),
            )
            raise
        logger.info(
            "Chat provider call | stage=%s model=%s outcome=success duration_ms=%d",
            stage,
            self._active_model_name,
            int((time.monotonic() - started_at) * 1000),
        )
        return response

    def send_message(self, user_message: str) -> str:
        """
        Send a user message and return the assistant's response.

        Handles function calling automatically: if Gemini requests
        tool calls, executes them and sends results back until
        Gemini produces a final text response.

        Bare small talk (a standalone greeting, thanks, or farewell —
        see _try_casual_shortcut) is answered instantly without
        touching Gemini at all, since it never needs tools or the
        fact/analysis/recommendation structure the real model is
        prompted to use for substantive questions.
        """
        if not user_message or not user_message.strip():
            return (
                "It looks like you sent an empty message. How can I help "
                "you with your productivity today? / يبدو إنك بعتّ رسالة "
                "فاضية، عايز مساعدة في إيه النهاردة؟"
            )
        if len(user_message) > MAX_AI_INPUT_CHARS:
            return "That message is too long to process safely. Please shorten it and try again."

        casual_reply = _try_casual_shortcut(user_message)
        if casual_reply is not None:
            return casual_reply

        try:
            self._rate_limiter.check(str(self.user_id))
        except RateLimitExceeded:
            messages = _ERROR_MESSAGES_AR if _is_arabic(user_message) else _ERROR_MESSAGES_EN
            return messages["quota"]

        try:
            return self._send_with_tools(user_message)

        except Exception as exc:
            if (
                _is_transient_provider_error(exc)
                and self._active_model_name != self._fallback_model_name
            ):
                logger.warning(
                    "Primary AI model temporarily unavailable; using configured fallback "
                    "| error_type=%s",
                    type(exc).__name__,
                )
                try:
                    self._chat = self._create_chat(self._fallback_model_name)
                    self._active_model_name = self._fallback_model_name
                    return self._send_with_tools(user_message)
                except Exception as fallback_exc:
                    exc = fallback_exc
            category = _classify_and_log_error(exc, context="send_message")
            messages = _ERROR_MESSAGES_AR if _is_arabic(user_message) else _ERROR_MESSAGES_EN
            return messages[category]

    def _handle_function_calls(self, response: Any) -> Any:
        """
        Process function calls from Gemini, executing tools and
        returning results until Gemini produces a final text response.
        """
        from google.genai import types

        rounds = 0
        while rounds < self._max_tool_rounds:
            # Check if there are any function calls in the response
            function_calls = []
            try:
                if response.candidates and response.candidates[0].content:
                    for part in response.candidates[0].content.parts:
                        if part.function_call and part.function_call.name:
                            function_calls.append(part)
            except (AttributeError, TypeError, IndexError):
                break

            if not function_calls:
                break  # No function calls — Gemini has a text response

            # Execute each function call and collect responses
            function_response_parts = []
            for part in function_calls:
                fc = part.function_call
                tool_name = fc.name
                tool_args = dict(fc.args) if fc.args else {}

                logger.info(
                    "Chatbot tool call | round=%d tool=%s",
                    rounds + 1,
                    tool_name if tool_name in _TOOL_DISPATCH else "unknown",
                )

                result_json = _execute_tool(
                    tool_name, tool_args, self.user_id, db=self.db,
                )

                function_response_parts.append(
                    types.Part(
                        function_response=types.FunctionResponse(
                            name=tool_name,
                            response={"result": result_json},
                        )
                    )
                )

            # Send all function responses back to Gemini
            response = self._send_provider_message(
                function_response_parts,
                stage=f"tool_round_{rounds + 1}",
            )
            rounds += 1

        return response

    @staticmethod
    def _extract_text(response: Any) -> str:
        """Extract text content from a Gemini response."""
        try:
            if response.candidates and response.candidates[0].content:
                parts = response.candidates[0].content.parts
                text_parts = [part.text for part in parts if part.text]
                if text_parts:
                    return "\n".join(text_parts)
            # Fallback: try the .text shortcut
            if hasattr(response, "text") and response.text:
                return response.text
            return (
                "I processed your request but didn't generate a text response. "
                "Could you try asking again? / "
                "معرفتش أطلعلك رد نصي، ممكن تجرب تسأل تاني؟"
            )
        except (AttributeError, IndexError, ValueError):
            return (
                "I had trouble processing the response. Please try again. / "
                "حصلت مشكلة في معالجة الرد، جرب تاني."
            )


    def send_message_stream(self, user_message: str):
        """
        Generator that yields text chunks for real-time streaming.

        Function calls are handled with non-streaming (fast tool
        decisions). The final text response is streamed chunk-by-chunk
        so the user sees text appearing progressively.

        Bare small talk is yielded instantly as a single chunk without
        touching Gemini at all — see send_message's docstring for why.
        """
        from google.genai import types

        if not user_message or not user_message.strip():
            yield (
                "It looks like you sent an empty message. "
                "How can I help you with your productivity today?"
            )
            return
        if len(user_message) > MAX_AI_INPUT_CHARS:
            yield "That message is too long to process safely. Please shorten it and try again."
            return

        casual_reply = _try_casual_shortcut(user_message)
        if casual_reply is not None:
            yield casual_reply
            return

        try:
            self._rate_limiter.check(str(self.user_id))
        except RateLimitExceeded:
            messages = _ERROR_MESSAGES_AR if _is_arabic(user_message) else _ERROR_MESSAGES_EN
            yield messages["quota"]
            return

        try:
            # Phase 1: Send initial message via streaming
            stream = self._chat.send_message_stream(user_message)

            # Collect the stream — it may contain text OR function calls
            collected_fc = []
            for chunk in stream:
                try:
                    if chunk.text:
                        yield chunk.text  # Direct text (no tools needed)
                except (AttributeError, ValueError):
                    pass
                # Also check for function calls in the chunk
                try:
                    if chunk.candidates and chunk.candidates[0].content:
                        for p in chunk.candidates[0].content.parts:
                            if p.function_call and p.function_call.name:
                                collected_fc.append(p)
                except (AttributeError, IndexError, TypeError):
                    pass

            # If we already yielded text and no function calls, we're done
            if not collected_fc:
                return

            # Phase 2: Handle function calls
            fc_parts = collected_fc
            rounds = 0

            while fc_parts and rounds < self._max_tool_rounds:
                # Execute all requested tools
                fr_response = []
                for p in fc_parts:
                    fc = p.function_call
                    name = fc.name
                    args = dict(fc.args) if fc.args else {}
                    logger.info(
                        "Chatbot streaming tool call | round=%d tool=%s",
                        rounds + 1,
                        name if name in _TOOL_DISPATCH else "unknown",
                    )
                    result = _execute_tool(name, args, self.user_id, db=self.db)
                    fr_response.append(
                        types.Part(
                            function_response=types.FunctionResponse(
                                name=name,
                                response={"result": result},
                            )
                        )
                    )

                rounds += 1
                fc_parts = []  # Reset for potential next round

                # Stream the response after sending tool results
                for chunk in self._chat.send_message_stream(fr_response):
                    try:
                        if chunk.text:
                            yield chunk.text
                    except (AttributeError, ValueError):
                        pass
                    # Check for additional function calls (rare)
                    try:
                        if chunk.candidates and chunk.candidates[0].content:
                            for p in chunk.candidates[0].content.parts:
                                if p.function_call and p.function_call.name:
                                    fc_parts.append(p)
                    except (AttributeError, IndexError, TypeError):
                        pass

        except Exception as exc:
            yield self._format_error(exc, user_message)

    def _format_error(self, exc: Exception, user_message: str = "") -> str:
        """Format an exception into a user-friendly error message."""
        category = _classify_and_log_error(exc, context="send_message_stream")
        messages = _ERROR_MESSAGES_AR if _is_arabic(user_message) else _ERROR_MESSAGES_EN
        return messages[category]


def _local_grounded_reply(user_id: str, user_message: str, *, db=None) -> str:
    """Return a useful, data-grounded answer when the provider exceeds its deadline."""
    arabic = _is_arabic(user_message)
    try:
        today = _impl_get_today_tasks(user_id, db=db)
    except Exception as exc:
        logger.warning(
            "Local coach fallback could not load today's plan | error_type=%s",
            type(exc).__name__,
        )
        today = {"has_plan": False}

    if not today.get("has_plan"):
        if arabic:
            return (
                "خدمة المدرب الذكي بتاخد وقت أطول من المعتاد حاليًا. "
                "مفيش خطة محفوظة لليوم، فابدأ بإضافة أهم مهمة واحدة وحدد لها وقتًا واقعيًا."
            )
        return (
            "The AI coach is taking longer than usual. You do not have a saved plan "
            "for today, so start by adding one important task with a realistic duration."
        )

    tasks = list(today.get("tasks") or [])
    unfinished = [task for task in tasks if task.get("status") != "completed"]
    unfinished.sort(
        key=lambda task: (
            int(task.get("priority") or 3),
            str(task.get("scheduled_start") or "99:99"),
        )
    )
    if not unfinished:
        if arabic:
            return (
                "خدمة المدرب الذكي بتاخد وقت أطول من المعتاد، لكن حسب خطتك المحفوظة "
                "كل مهام اليوم مكتملة. راجع النتائج أو حضّر أول أولوية لبكرة."
            )
        return (
            "The AI coach is taking longer than usual, but your saved plan shows that "
            "all of today's tasks are complete. Review the results or prepare tomorrow's first priority."
        )

    first = unfinished[0]
    title = str(first.get("title") or "your highest-priority task").strip()[:120]
    minutes = max(0, int(first.get("estimated_minutes") or 0))
    remaining_minutes = sum(
        max(0, int(task.get("estimated_minutes") or 0)) for task in unfinished
    )
    if arabic:
        duration = f" لمدة {minutes} دقيقة" if minutes else ""
        return (
            "خدمة المدرب الذكي بتاخد وقت أطول من المعتاد، فده ملخص سريع من خطتك المحفوظة: "
            f"عندك {len(unfinished)} مهام غير مكتملة بإجمالي حوالي {remaining_minutes} دقيقة. "
            f"ابدأ بـ «{title}»{duration}، وبعدها راجع باقي الأولويات."
        )
    duration = f" for {minutes} minutes" if minutes else ""
    return (
        "The AI coach is taking longer than usual, so here is a quick answer from your saved plan: "
        f"you have {len(unfinished)} unfinished task(s), about {remaining_minutes} minutes total. "
        f"Start with “{title}”{duration}, then review the remaining priorities."
    )


def send_message_bounded(user_id: str, user_message: str, *, db=None) -> str:
    """Run provider-backed chat in an isolated process with a hard total deadline."""
    if not user_message or not user_message.strip():
        return (
            "It looks like you sent an empty message. How can I help with your "
            "productivity today?"
        )
    if len(user_message) > MAX_AI_INPUT_CHARS:
        return "That message is too long to process safely. Please shorten it and try again."
    casual_reply = _try_casual_shortcut(user_message)
    if casual_reply is not None:
        return casual_reply
    if not _get_api_key():
        raise ValueError("The AI service is not configured.")

    worker = Path(__file__).with_name("chatbot_worker.py")
    payload = json.dumps(
        {"user_id": str(user_id), "message": user_message},
        ensure_ascii=False,
    )
    try:
        completed = subprocess.run(
            [sys.executable, str(worker)],
            input=payload,
            stdout=subprocess.PIPE,
            stderr=None,
            text=True,
            encoding="utf-8",
            timeout=_chat_process_timeout_seconds(),
            check=False,
            cwd=str(worker.parent),
        )
        if completed.returncode == 0:
            decoded = json.loads(completed.stdout)
            reply = decoded.get("reply")
            if isinstance(reply, str) and reply.strip():
                return reply.strip()[: MAX_AI_OUTPUT_TOKENS * 4]
        logger.warning(
            "Isolated AI chat failed; using local grounded fallback | return_code=%s",
            completed.returncode,
        )
    except subprocess.TimeoutExpired:
        logger.warning("Isolated AI chat exceeded its total deadline; using local fallback")
    except (OSError, UnicodeError, json.JSONDecodeError, TypeError) as exc:
        logger.warning(
            "Isolated AI chat could not be read; using local fallback | error_type=%s",
            type(exc).__name__,
        )
    return _local_grounded_reply(user_id, user_message, db=db)


# ─────────────────────────────────────────────────────────────
# Public Factory Functions
# ─────────────────────────────────────────────────────────────

def create_chatbot_service(user_id: str, *, db=None) -> ChatbotService:
    """Create a new ChatbotService instance for the given user."""
    return ChatbotService(user_id=user_id, db=db)


def is_api_key_configured() -> bool:
    """Check whether a Gemini API key is available."""
    return bool(_get_api_key())
