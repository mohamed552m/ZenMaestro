import os
import re
import sqlite3  # kept only for type hints (sqlite3.Row / sqlite3.Cursor) below —
                # the actual connection no longer uses this module directly.
from contextlib import contextmanager
from datetime import date, datetime, timedelta, timezone
from itertools import count
from pathlib import Path
from typing import Any, Optional
import json
import uuid

from sqlalchemy import (
    create_engine, text, inspect,
    MetaData, Table, Column, Integer, Float, String, Text,
    DateTime, Date, Time, ForeignKey, UniqueConstraint, CheckConstraint,
    Index, func,
)
from sqlalchemy.engine import Connection, CursorResult, Engine, make_url
from sqlalchemy.exc import DBAPIError, IntegrityError, SQLAlchemyError
from dotenv import load_dotenv

from timezone_utils import get_user_timezone, safe_zoneinfo, local_hour_from_utc_string, user_today
from config import MAX_SEGMENTS_PER_TASK, MIN_SEGMENT_MINUTES, WORK_DAY_END


class OwnershipError(Exception):
    """
    Raised whenever a caller tries to read or mutate a plan/task that
    either doesn't exist or doesn't belong to the given user_id.

    Deliberately does not distinguish "not found" from "not yours" in
    its message -- leaking that distinction back to a caller is itself
    a (minor) information disclosure about which raw ids are valid.
    """


class TimerConflictError(Exception):
    """
    Raised by Database.set_task_timer_state() when the caller passed an
    expected_version that no longer matches the row's current
    timer_version -- i.e. some other request already updated this
    task's timer state in between this caller's read and its write.

    This is the optimistic-concurrency counterpart to OwnershipError:
    the task exists and IS owned by this user (that case raises
    OwnershipError instead), but a concurrent update was lost if this
    write were allowed to blindly overwrite it. Callers should re-read
    the task and retry (once) rather than silently dropping the
    conflicting update.
    """


class StaleScheduleError(Exception):
    """Raised when scheduling or Undo targets an obsolete plan revision."""


# ---------------------------------------------------------------------------
# Schema — dialect-agnostic table definitions
#
# This is the SQLAlchemy equivalent of schema.sql. It's what makes a FRESH
# Postgres database creatable directly (metadata.create_all(engine)) —
# instead of running the SQLite-only schema.sql text file. An existing
# SQLite database keeps using schema.sql + the self-healing migrations
# below, exactly as before, so nothing changes for local development.
# ---------------------------------------------------------------------------

metadata = MetaData()

users = Table(
    "users", metadata,
    Column("user_id", String, primary_key=True),
    Column("email", String, nullable=False, unique=True),
    Column("display_name", String, nullable=False),
    Column("timezone", String, nullable=False, server_default="UTC"),
    Column("password_hash", Text),
    Column("is_disabled", Integer, nullable=False, server_default="0"),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("updated_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    CheckConstraint("is_disabled IN (0, 1)", name="ck_users_disabled"),
)
Index("idx_users_email", users.c.email)

categories = Table(
    "categories", metadata,
    Column("category_id", Integer, primary_key=True, autoincrement=True),
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("name", String, nullable=False),
    Column("color", String, nullable=False, server_default="#3B82F6"),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint("user_id", "name"),
)
Index("idx_categories_user_id", categories.c.user_id)

plans = Table(
    "plans", metadata,
    Column("plan_id", Integer, primary_key=True, autoincrement=True),
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("plan_date", Date, nullable=False),
    Column("raw_input", Text, nullable=False),
    Column("ai_summary", Text),
    Column("status", String, nullable=False, server_default="active"),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("schedule_revision", Integer, nullable=False, server_default="0"),
    UniqueConstraint("user_id", "plan_date"),
    CheckConstraint("status IN ('draft','active','completed')", name="ck_plans_status"),
)
Index("idx_plans_user_id", plans.c.user_id)
Index("idx_plans_date", plans.c.plan_date)
Index("idx_plans_user_date", plans.c.user_id, plans.c.plan_date)

tasks = Table(
    "tasks", metadata,
    Column("task_id", Integer, primary_key=True, autoincrement=True),
    Column("plan_id", Integer, ForeignKey("plans.plan_id", ondelete="CASCADE"), nullable=False),
    Column("category_id", Integer, ForeignKey("categories.category_id", ondelete="SET NULL")),
    Column("title", String, nullable=False),
    Column("description", Text),
    Column("priority", Integer, nullable=False, server_default="3"),
    Column("estimated_minutes", Integer, nullable=False, server_default="30"),
    Column("scheduled_start", Time),
    Column("scheduled_end", Time),
    Column("is_fixed_time", Integer, nullable=False, server_default="0"),
    Column("is_break", Integer, nullable=False, server_default="0"),
    Column("order_index", Integer, nullable=False, server_default="0"),
    Column("status", String, nullable=False, server_default="pending"),
    Column("failure_reason", String),
    Column("actual_minutes", Integer),
    Column("started_at", DateTime),
    Column("completed_at", DateTime),
    Column("timer_accumulated_seconds", Integer, nullable=False, server_default="0"),
    Column("timer_segment_started_at", DateTime),
    Column("pause_count", Integer, nullable=False, server_default="0"),
    Column("paused_at", DateTime),
    Column("timer_total_paused_seconds", Integer, nullable=False, server_default="0"),
    Column("timer_version", Integer, nullable=False, server_default="0"),
    Column("version", Integer, nullable=False, server_default="0"),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("updated_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("google_event_id", String),
    Column("google_exported_at", DateTime),
    Column("earliest_start", DateTime),
    Column("deadline", DateTime),
    CheckConstraint("priority BETWEEN 1 AND 5", name="ck_tasks_priority"),
    CheckConstraint("estimated_minutes >= 0", name="ck_tasks_est_minutes"),
    CheckConstraint("status IN ('pending','in_progress','completed','failed')", name="ck_tasks_status"),
    CheckConstraint(
        "failure_reason IN ('Harder than expected','Distracted','Tired',"
        "'Unexpected event','Changed priorities','Ran out of time')",
        name="ck_tasks_failure_reason",
    ),
    CheckConstraint("actual_minutes >= 0", name="ck_tasks_actual_minutes"),
    CheckConstraint("is_fixed_time IN (0, 1)", name="ck_tasks_is_fixed_time"),
    CheckConstraint("is_break IN (0, 1)", name="ck_tasks_is_break"),
    CheckConstraint("version >= 0", name="ck_tasks_version"),
)
Index("idx_tasks_plan_id", tasks.c.plan_id)
Index("idx_tasks_category_id", tasks.c.category_id)
Index("idx_tasks_status", tasks.c.status)
Index("idx_tasks_plan_status", tasks.c.plan_id, tasks.c.status)

task_segments = Table(
    "task_segments", metadata,
    Column("segment_id", Integer, primary_key=True, autoincrement=True),
    Column("task_id", Integer, ForeignKey("tasks.task_id", ondelete="CASCADE"), nullable=False),
    Column("segment_index", Integer, nullable=False),
    Column("scheduled_start", Time, nullable=False),
    Column("scheduled_end", Time, nullable=False),
    Column("scheduled_date", Date),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("google_event_id", String),
    Column("google_calendar_id", String),
    Column("calendar_provenance", String, nullable=False, server_default="legacy_unknown"),
    Column("calendar_sync_status", String, nullable=False, server_default="not_synced"),
    Column("calendar_sync_error", Text),
    UniqueConstraint("task_id", "segment_index"),
    CheckConstraint("segment_index >= 0", name="ck_task_segments_index"),
    CheckConstraint("scheduled_end > scheduled_start", name="ck_task_segments_same_day"),
    CheckConstraint(
        "calendar_provenance IN ('imported','coachai_created','legacy_unknown')",
        name="ck_task_segments_provenance",
    ),
)
Index("idx_task_segments_task_id", task_segments.c.task_id)
Index("idx_task_segments_date", task_segments.c.scheduled_date)
Index("idx_task_segments_google_event", task_segments.c.google_event_id)

schedule_operations = Table(
    "schedule_operations", metadata,
    Column("operation_id", String, primary_key=True),
    Column("plan_id", Integer, ForeignKey("plans.plan_id", ondelete="CASCADE"), nullable=False),
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("base_revision", Integer, nullable=False),
    Column("result_revision", Integer, nullable=False),
    Column("before_state", Text, nullable=False),
    Column("after_state", Text, nullable=False),
    Column("status", String, nullable=False, server_default="applied"),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("undone_at", DateTime),
    Column("calendar_reconciled_at", DateTime),
    Column("calendar_reconciliation_error", Text),
    CheckConstraint("status IN ('applied','undone')", name="ck_schedule_operations_status"),
)
Index("idx_schedule_operations_plan", schedule_operations.c.plan_id, schedule_operations.c.created_at)
Index("idx_schedule_operations_user", schedule_operations.c.user_id)

# Notification foundation. These tables intentionally contain no provider-
# specific Firebase/APNs fields beyond an opaque device token and provider
# name. Business policy and delivery remain separate layers.
notification_preferences = Table(
    "notification_preferences", metadata,
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), primary_key=True),
    Column("tone", String, nullable=False, server_default="neutral"),
    Column("default_reminder_lead_minutes", Integer, server_default="10"),
    Column("quiet_hours_start", Time),
    Column("quiet_hours_end", Time),
    Column("daily_summary_enabled", Integer, nullable=False, server_default="0"),
    Column("coach_checkins_enabled", Integer, nullable=False, server_default="0"),
    Column("privacy_mode", String, nullable=False, server_default="show_title"),
    Column("sound_enabled", Integer, nullable=False, server_default="1"),
    Column("vibration_enabled", Integer, nullable=False, server_default="1"),
    Column("multi_device_policy", String, nullable=False, server_default="primary"),
    Column("locale", String, nullable=False, server_default="en"),
    Column("time_format", String, nullable=False, server_default="12h"),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("updated_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    CheckConstraint("tone IN ('neutral','friendly','coach')", name="ck_notification_preferences_tone"),
    CheckConstraint(
        "default_reminder_lead_minutes IS NULL OR default_reminder_lead_minutes IN (0,5,10,15,30)",
        name="ck_notification_preferences_lead",
    ),
    CheckConstraint("daily_summary_enabled IN (0,1)", name="ck_notification_preferences_daily"),
    CheckConstraint("coach_checkins_enabled IN (0,1)", name="ck_notification_preferences_checkins"),
    CheckConstraint("privacy_mode IN ('show_title','hide_title','generic')", name="ck_notification_preferences_privacy"),
    CheckConstraint("sound_enabled IN (0,1)", name="ck_notification_preferences_sound"),
    CheckConstraint("vibration_enabled IN (0,1)", name="ck_notification_preferences_vibration"),
    CheckConstraint("multi_device_policy IN ('all','primary','selected')", name="ck_notification_preferences_devices"),
    CheckConstraint("locale IN ('ar','en')", name="ck_notification_preferences_locale"),
    CheckConstraint("time_format IN ('12h','24h')", name="ck_notification_preferences_time_format"),
)

notification_type_preferences = Table(
    "notification_type_preferences", metadata,
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("notification_type", String, nullable=False),
    Column("enabled", Integer, nullable=False, server_default="1"),
    Column("preferred_channel", String, nullable=False, server_default="in_app"),
    Column("lead_minutes", Integer),
    Column("cooldown_seconds", Integer, nullable=False, server_default="0"),
    Column("interruption_level", String, nullable=False, server_default="passive"),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("updated_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint("user_id", "notification_type"),
    CheckConstraint("enabled IN (0,1)", name="ck_notification_type_preferences_enabled"),
    CheckConstraint("preferred_channel IN ('in_app','local','push')", name="ck_notification_type_preferences_channel"),
    CheckConstraint("lead_minutes IS NULL OR lead_minutes IN (0,5,10,15,30)", name="ck_notification_type_preferences_lead"),
    CheckConstraint("cooldown_seconds >= 0", name="ck_notification_type_preferences_cooldown"),
    CheckConstraint("interruption_level IN ('passive','active','time_sensitive')", name="ck_notification_type_preferences_interruption"),
)
Index("idx_notification_type_preferences_user", notification_type_preferences.c.user_id)

notification_devices = Table(
    "notification_devices", metadata,
    Column("device_id", String, primary_key=True),
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("platform", String, nullable=False),
    Column("provider", String, nullable=False),
    Column("push_token", Text),
    Column("app_identifier", String),
    Column("provider_metadata", Text, nullable=False, server_default="{}"),
    Column("app_version", String),
    Column("locale", String, nullable=False, server_default="en"),
    Column("timezone", String, nullable=False, server_default="UTC"),
    Column("is_primary", Integer, nullable=False, server_default="0"),
    Column("is_enabled", Integer, nullable=False, server_default="1"),
    Column("last_seen_at", DateTime),
    Column("token_invalidated_at", DateTime),
    Column("last_acknowledged_schedule_revision", Integer, nullable=False, server_default="0"),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("updated_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint("provider", "push_token"),
    CheckConstraint("platform IN ('ios','android')", name="ck_notification_devices_platform"),
    CheckConstraint("provider IN ('apns','fcm','none')", name="ck_notification_devices_provider"),
    CheckConstraint("locale IN ('ar','en')", name="ck_notification_devices_locale"),
    CheckConstraint("is_primary IN (0,1)", name="ck_notification_devices_primary"),
    CheckConstraint("is_enabled IN (0,1)", name="ck_notification_devices_enabled"),
    CheckConstraint("last_acknowledged_schedule_revision >= 0", name="ck_notification_devices_revision"),
)
Index("idx_notification_devices_user", notification_devices.c.user_id)
Index("idx_notification_devices_token", notification_devices.c.push_token)

notification_outbox = Table(
    "notification_outbox", metadata,
    Column("sequence_id", Integer, primary_key=True, autoincrement=True),
    Column("outbox_id", String, nullable=False, unique=True),
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("event_type", String, nullable=False),
    Column("task_id", Integer),
    Column("segment_id", Integer),
    Column("scheduled_date", Date),
    Column("segment_index", Integer),
    Column("schedule_revision", Integer),
    Column("schedule_operation_id", String),
    Column("event_payload", Text, nullable=False),
    Column("idempotency_key", String, nullable=False),
    Column("status", String, nullable=False, server_default="queued"),
    Column("attempts", Integer, nullable=False, server_default="0"),
    Column("available_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("locked_at", DateTime),
    Column("locked_by", String),
    Column("expires_at", DateTime),
    Column("processed_at", DateTime),
    Column("last_error", Text),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint("user_id", "idempotency_key"),
    CheckConstraint("status IN ('queued','processing','processed','failed','cancelled','expired')", name="ck_notification_outbox_status"),
    CheckConstraint("attempts >= 0", name="ck_notification_outbox_attempts"),
    CheckConstraint("segment_index IS NULL OR segment_index >= 0", name="ck_notification_outbox_segment_index"),
)
Index("idx_notification_outbox_ready", notification_outbox.c.status, notification_outbox.c.available_at)
Index("idx_notification_outbox_lease", notification_outbox.c.status, notification_outbox.c.locked_at)
Index("idx_notification_outbox_user", notification_outbox.c.user_id, notification_outbox.c.created_at)

notifications = Table(
    "notifications", metadata,
    Column("notification_id", String, primary_key=True),
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("notification_type", String, nullable=False),
    Column("urgency", String, nullable=False),
    Column("interruption_level", String, nullable=False),
    Column("task_id", Integer),
    Column("segment_id", Integer),
    Column("scheduled_date", Date),
    Column("segment_index", Integer),
    Column("schedule_revision", Integer),
    Column("schedule_operation_id", String),
    Column("title", Text, nullable=False),
    Column("body", Text, nullable=False),
    Column("locale", String, nullable=False),
    Column("template_id", String, nullable=False),
    Column("template_version", Integer, nullable=False),
    Column("scheduled_for", DateTime, nullable=False),
    Column("expires_at", DateTime, nullable=False),
    Column("status", String, nullable=False, server_default="active"),
    Column("dedupe_key", String, nullable=False),
    Column("suppression_reason", String),
    Column("action_metadata", Text, nullable=False, server_default="{}"),
    Column("read_at", DateTime),
    Column("acted_at", DateTime),
    Column("sync_revision", Integer, nullable=False, server_default="0"),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("updated_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint("user_id", "dedupe_key"),
    CheckConstraint("urgency IN ('critical','high','normal','low')", name="ck_notifications_urgency"),
    CheckConstraint("interruption_level IN ('passive','active','time_sensitive')", name="ck_notifications_interruption"),
    CheckConstraint("locale IN ('ar','en')", name="ck_notifications_locale"),
    CheckConstraint("template_version >= 1", name="ck_notifications_template_version"),
    CheckConstraint("status IN ('active','suppressed','expired','cancelled')", name="ck_notifications_status"),
    CheckConstraint("segment_index IS NULL OR segment_index >= 0", name="ck_notifications_segment_index"),
    CheckConstraint("sync_revision >= 0", name="ck_notifications_sync_revision"),
)
Index("idx_notifications_user_created", notifications.c.user_id, notifications.c.created_at)
Index("idx_notifications_user_unread", notifications.c.user_id, notifications.c.read_at)
Index("idx_notifications_task", notifications.c.user_id, notifications.c.task_id)
Index("idx_notifications_sync", notifications.c.user_id, notifications.c.sync_revision)

notification_deliveries = Table(
    "notification_deliveries", metadata,
    Column("delivery_id", String, primary_key=True),
    Column("notification_id", String, ForeignKey("notifications.notification_id", ondelete="CASCADE"), nullable=False),
    Column("channel", String, nullable=False),
    Column("device_id", String, ForeignKey("notification_devices.device_id", ondelete="SET NULL")),
    Column("target_key", String, nullable=False, server_default="account"),
    Column("provider_message_id", String),
    Column("provider", String),
    Column("status", String, nullable=False, server_default="queued"),
    Column("queued_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("sent_at", DateTime),
    Column("delivered_at", DateTime),
    Column("read_at", DateTime),
    Column("acted_at", DateTime),
    Column("cancelled_at", DateTime),
    Column("retry_count", Integer, nullable=False, server_default="0"),
    Column("next_attempt_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("last_attempt_at", DateTime),
    Column("accepted_at", DateTime),
    Column("locked_at", DateTime),
    Column("locked_by", String),
    Column("error_classification", String),
    Column("last_error", Text),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("updated_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint("notification_id", "channel", "target_key"),
    CheckConstraint("channel IN ('in_app','local','push')", name="ck_notification_deliveries_channel"),
    CheckConstraint("status IN ('queued','sent','delivered','failed','cancelled')", name="ck_notification_deliveries_status"),
    CheckConstraint("retry_count >= 0", name="ck_notification_deliveries_retries"),
)
Index("idx_notification_deliveries_notification", notification_deliveries.c.notification_id)
Index("idx_notification_deliveries_device", notification_deliveries.c.device_id)
Index("idx_notification_deliveries_push_ready", notification_deliveries.c.channel, notification_deliveries.c.status, notification_deliveries.c.next_attempt_at)
Index("idx_notification_deliveries_lease", notification_deliveries.c.channel, notification_deliveries.c.locked_at)

notification_local_schedules = Table(
    "notification_local_schedules", metadata,
    Column("local_schedule_id", String, primary_key=True),
    Column("notification_id", String, ForeignKey("notifications.notification_id", ondelete="CASCADE"), nullable=False),
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("device_id", String, ForeignKey("notification_devices.device_id", ondelete="SET NULL")),
    Column("target_key", String, nullable=False, server_default="account"),
    Column("task_id", Integer),
    Column("segment_id", Integer),
    Column("segment_index", Integer),
    Column("schedule_revision", Integer),
    Column("schedule_operation_id", String),
    Column("trigger_type", String, nullable=False, server_default="time"),
    Column("scheduled_fire_at", DateTime, nullable=False),
    Column("expires_at", DateTime, nullable=False),
    Column("status", String, nullable=False, server_default="scheduled"),
    Column("payload", Text, nullable=False, server_default="{}"),
    Column("locale", String, nullable=False),
    Column("timezone", String, nullable=False),
    Column("cancelled_at", DateTime),
    Column("cancellation_reason", String),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("updated_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint("notification_id", "target_key"),
    CheckConstraint("status IN ('scheduled','cancelled','expired','fired')", name="ck_local_schedule_status"),
    CheckConstraint("locale IN ('ar','en')", name="ck_local_schedule_locale"),
    CheckConstraint("segment_index IS NULL OR segment_index >= 0", name="ck_local_schedule_segment_index"),
)
Index("idx_local_schedules_user_fire", notification_local_schedules.c.user_id, notification_local_schedules.c.status, notification_local_schedules.c.scheduled_fire_at)
Index("idx_local_schedules_task", notification_local_schedules.c.user_id, notification_local_schedules.c.task_id, notification_local_schedules.c.status)

notification_sync_state = Table(
    "notification_sync_state", metadata,
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), primary_key=True),
    Column("sync_revision", Integer, nullable=False, server_default="0"),
    Column("updated_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    CheckConstraint("sync_revision >= 0", name="ck_notification_sync_revision"),
)

task_notification_state = Table(
    "task_notification_state", metadata,
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("task_id", Integer, ForeignKey("tasks.task_id", ondelete="CASCADE"), nullable=False),
    Column("state", String, nullable=False, server_default="SAFE"),
    Column("last_evaluated_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("last_transition_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint("user_id", "task_id"),
    CheckConstraint("state IN ('SAFE','AT_RISK','CRITICAL','PASSED')", name="ck_task_notification_state"),
)
Index("idx_task_notification_state_user", task_notification_state.c.user_id)

user_profiles = Table(
    "user_profiles", metadata,
    Column("profile_id", Integer, primary_key=True, autoincrement=True),
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False, unique=True),
    Column("completion_rate", Float, nullable=False, server_default="0.0"),
    Column("productivity_score", Float, nullable=False, server_default="0.0"),
    Column("best_productivity_hour", Integer),
    Column("avg_delay_minutes", Float, nullable=False, server_default="0.0"),
    Column("main_failure_reason", String),
    Column("favorite_category_id", Integer, ForeignKey("categories.category_id", ondelete="SET NULL")),
    Column("current_streak", Integer, nullable=False, server_default="0"),
    Column("longest_streak", Integer, nullable=False, server_default="0"),
    Column("total_completed", Integer, nullable=False, server_default="0"),
    Column("total_failed", Integer, nullable=False, server_default="0"),
    Column("total_tasks", Integer, nullable=False, server_default="0"),
    Column("last_updated", DateTime, nullable=False, server_default=func.current_timestamp()),
    CheckConstraint("completion_rate BETWEEN 0 AND 1", name="ck_profiles_completion_rate"),
    CheckConstraint("productivity_score BETWEEN 0 AND 100", name="ck_profiles_productivity_score"),
    CheckConstraint("best_productivity_hour BETWEEN 0 AND 23", name="ck_profiles_hour"),
    CheckConstraint("avg_delay_minutes >= 0", name="ck_profiles_avg_delay"),
    CheckConstraint("current_streak >= 0", name="ck_profiles_current_streak"),
    CheckConstraint("longest_streak >= 0", name="ck_profiles_longest_streak"),
    CheckConstraint("total_completed >= 0", name="ck_profiles_total_completed"),
    CheckConstraint("total_failed >= 0", name="ck_profiles_total_failed"),
    CheckConstraint("total_tasks >= 0", name="ck_profiles_total_tasks"),
)
Index("idx_profiles_user_id", user_profiles.c.user_id)

badges = Table(
    "badges", metadata,
    Column("badge_id", Integer, primary_key=True, autoincrement=True),
    Column("name", String, nullable=False, unique=True),
    Column("description", String, nullable=False),
    Column("icon", String),
    Column("requirement_type", String, nullable=False),
    Column("requirement_value", Integer, nullable=False),
    CheckConstraint("requirement_type IN ('streak','count','rate')", name="ck_badges_req_type"),
    CheckConstraint("requirement_value >= 0", name="ck_badges_req_value"),
)

user_badges = Table(
    "user_badges", metadata,
    Column("user_badge_id", Integer, primary_key=True, autoincrement=True),
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("badge_id", Integer, ForeignKey("badges.badge_id", ondelete="CASCADE"), nullable=False),
    Column("earned_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint("user_id", "badge_id"),
)
Index("idx_user_badges_user_id", user_badges.c.user_id)

google_oauth_tokens = Table(
    "google_oauth_tokens", metadata,
    Column("token_id", Integer, primary_key=True, autoincrement=True),
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False, unique=True),
    Column("access_token", Text, nullable=False),
    Column("refresh_token", Text, nullable=False),
    Column("token_expiry", DateTime, nullable=False),
    Column("scopes", String, nullable=False, server_default=""),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("updated_at", DateTime, nullable=False, server_default=func.current_timestamp()),
)

google_selected_calendars = Table(
    "google_selected_calendars", metadata,
    Column("selection_id", Integer, primary_key=True, autoincrement=True),
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("calendar_id", String, nullable=False),
    Column("calendar_name", String, nullable=False, server_default=""),
    Column("color", String, nullable=False, server_default="#4285F4"),
    Column("is_primary", Integer, nullable=False, server_default="0"),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint("user_id", "calendar_id"),
)
Index("idx_gcal_selected_user", google_selected_calendars.c.user_id)

google_calendar_events = Table(
    "google_calendar_events", metadata,
    Column("event_id", Integer, primary_key=True, autoincrement=True),
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("google_event_id", String, nullable=False),
    Column("title", String, nullable=False, server_default=""),
    Column("start_time", Time, nullable=False),
    Column("end_time", Time, nullable=False),
    Column("event_date", Date, nullable=False),
    Column("calendar_id", String, nullable=False),
    Column("last_synced_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint("user_id", "google_event_id", "event_date"),
)
Index("idx_gcal_events_user_date", google_calendar_events.c.user_id, google_calendar_events.c.event_date)

# Mobile-safe command replay and application-data synchronization.  These are
# deliberately separate from notification sync: application changes include
# plans/tasks/segments/settings/calendar state and deletion tombstones.
idempotency_records = Table(
    "idempotency_records", metadata,
    Column("record_id", String, primary_key=True),
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("operation", String, nullable=False),
    Column("idempotency_key", String, nullable=False),
    Column("request_hash", String, nullable=False),
    Column("response_json", Text),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("completed_at", DateTime),
    UniqueConstraint("user_id", "operation", "idempotency_key"),
)
Index("idx_idempotency_user_created", idempotency_records.c.user_id, idempotency_records.c.created_at)

# Shared fixed-window counters for paid/high-cost operations.  Unlike the
# legacy process-local limiter, these rows coordinate every API/worker replica
# through the canonical database and survive process restarts.
rate_limit_buckets = Table(
    "rate_limit_buckets", metadata,
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), primary_key=True),
    Column("scope", String, primary_key=True),
    Column("window_key", Integer, primary_key=True),
    Column("request_count", Integer, nullable=False, server_default="0"),
    Column("expires_at", DateTime, nullable=False),
    Column("updated_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    CheckConstraint("request_count >= 0", name="ck_rate_limit_request_count"),
)
Index("idx_rate_limit_expiry", rate_limit_buckets.c.expires_at)

application_sync_state = Table(
    "application_sync_state", metadata,
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), primary_key=True),
    Column("sync_revision", Integer, nullable=False, server_default="0"),
    Column("minimum_available_revision", Integer, nullable=False, server_default="0"),
    Column("updated_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    CheckConstraint("sync_revision >= 0", name="ck_app_sync_revision"),
)

application_changes = Table(
    "application_changes", metadata,
    Column("sequence_id", Integer, primary_key=True, autoincrement=True),
    Column("change_id", String, nullable=False, unique=True),
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("sync_revision", Integer, nullable=False),
    Column("entity_type", String, nullable=False),
    Column("entity_id", String, nullable=False),
    Column("change_type", String, nullable=False),
    Column("entity_version", Integer),
    Column("payload", Text, nullable=False, server_default="{}"),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint("user_id", "sync_revision"),
    CheckConstraint("change_type IN ('upsert','delete')", name="ck_app_changes_type"),
)
Index("idx_app_changes_user_revision", application_changes.c.user_id, application_changes.c.sync_revision)

oauth_states = Table(
    "oauth_states", metadata,
    Column("state_hash", String, primary_key=True),
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("provider", String, nullable=False),
    Column("redirect_uri", Text, nullable=False),
    Column("code_verifier", Text),
    Column("expires_at", DateTime, nullable=False),
    Column("used_at", DateTime),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
)
Index("idx_oauth_states_user_expiry", oauth_states.c.user_id, oauth_states.c.expires_at)

calendar_sync_state = Table(
    "calendar_sync_state", metadata,
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("calendar_id", String, nullable=False),
    Column("range_start", Date, nullable=False),
    Column("range_end", Date, nullable=False),
    Column("sync_token", Text),
    Column("status", String, nullable=False, server_default="pending"),
    Column("last_error", Text),
    Column("last_success_at", DateTime),
    Column("updated_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint("user_id", "calendar_id"),
    CheckConstraint("status IN ('pending','running','fresh','error')", name="ck_calendar_sync_state_status"),
)

calendar_operations = Table(
    "calendar_operations", metadata,
    Column("operation_id", String, primary_key=True),
    Column("user_id", String, ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False),
    Column("operation_type", String, nullable=False),
    Column("idempotency_key", String, nullable=False),
    Column("payload", Text, nullable=False),
    Column("status", String, nullable=False, server_default="queued"),
    Column("attempts", Integer, nullable=False, server_default="0"),
    Column("available_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("locked_at", DateTime),
    Column("locked_by", String),
    Column("last_error", Text),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("completed_at", DateTime),
    UniqueConstraint("user_id", "operation_type", "idempotency_key"),
    CheckConstraint("status IN ('queued','processing','completed','failed','cancelled')", name="ck_calendar_operations_status"),
)
Index("idx_calendar_operations_ready", calendar_operations.c.status, calendar_operations.c.available_at)


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

DB_NAME: str = "coach_ai.db"
SCHEMA_FILE: str = "schema.sql"
ALEMBIC_HEAD: str = "20261009_0002"

# Set DATABASE_URL to point at Postgres in production, e.g.:
#   postgresql+psycopg2://<USER>:<PASSWORD>@host:5432/coachai
# Left unset (the default), CoachAI keeps using a local SQLite file — no
# other code in this file, or anywhere else in the project, needs to change
# to switch between the two.
load_dotenv(Path(__file__).with_name(".env"), override=False)

# No implicit SQLite fallback: production/development entry points must name
# their PostgreSQL database explicitly. Tests that intentionally exercise
# SQLite pass an explicit sqlite:/// URL to Database(...).
DATABASE_URL: Optional[str] = os.environ.get("DATABASE_URL")


# ---------------------------------------------------------------------------
# Database Class
# ---------------------------------------------------------------------------

class Database:
    """
    Database manager for CoachAI, backed by SQLAlchemy.

    Uses the explicitly configured DATABASE_URL (PostgreSQL for normal
    development/runtime) or an explicit per-instance URL used by tests.
    Every method below this class's
    connection-management section is UNCHANGED from the original sqlite3
    version — same names, same SQL, same ``?`` placeholders — because the
    low-level execute()/fetch_one()/fetch_all() helpers now translate
    everything through SQLAlchemy under the hood.

    Attributes:
        engine: SQLAlchemy Engine (connection pool + dialect).
        connection: Active SQLAlchemy Connection instance.
    """

    def __init__(
        self,
        db_url: Optional[str] = None,
        *,
        engine: Optional[Engine] = None,
        dispose_engine_on_close: bool = True,
        check_schema: bool = True,
    ) -> None:
        """
        Initialise the database manager.

        Args:
            db_url: Optional explicit SQLAlchemy connection URL. When omitted,
                    DATABASE_URL must be configured in the environment or .env.
        """
        selected_url = db_url or (str(engine.url) if engine is not None else None) or DATABASE_URL
        if not selected_url:
            raise RuntimeError(
                "DATABASE_URL is required. Configure a PostgreSQL URL in the "
                "environment or the repository-local .env file."
            )
        self.db_url: str = selected_url
        self.engine: Optional[Engine] = engine
        self.connection: Optional[Connection] = None
        self._dispose_engine_on_close = bool(dispose_engine_on_close)
        self._check_schema = bool(check_schema)
        self._transaction_depth: int = 0
        self._transaction_failed: bool = False
        try:
            self._connect()
        except Exception:
            self.close()
            raise

    # ------------------------------------------------------------------
    # Connection lifecycle
    # ------------------------------------------------------------------

    @staticmethod
    def create_pool(db_url: str) -> Engine:
        """Create the reusable SQLAlchemy pool described by typed settings."""
        from backend_config import BackendSettings

        settings = BackendSettings.from_env(database_url=db_url)
        engine_options: dict[str, Any] = {
            "future": True,
            "pool_pre_ping": True,
            "hide_parameters": True,
        }
        if make_url(db_url).get_backend_name() == "postgresql":
            # CoachAI persists instants as UTC and applies the user's IANA
            # timezone at the service/UI boundary. Pinning each PostgreSQL
            # session to UTC prevents machine-local timezone settings from
            # changing aware-datetime input or timestamp comparisons.
            engine_options.update({
                "connect_args": {"options": "-c timezone=UTC"},
                "pool_size": settings.db_pool_size,
                "max_overflow": settings.db_max_overflow,
                "pool_timeout": settings.db_pool_timeout_seconds,
                "pool_recycle": settings.db_pool_recycle_seconds,
            })
        return create_engine(db_url, **engine_options)

    def _connect(self) -> None:
        """Acquire one explicit connection and enable foreign keys (SQLite)."""
        if self.engine is None:
            self.engine = self.create_pool(self.db_url)
        self.connection = self.engine.connect()
        if self.is_sqlite:
            self.connection.execute(text("PRAGMA foreign_keys = ON"))
            self.connection.commit()
        if self._check_schema:
            self._maybe_create_schema()

    @property
    def is_sqlite(self) -> bool:
        """True when this Database is currently talking to SQLite."""
        return self.engine.dialect.name == "sqlite"

    @property
    def is_postgresql(self) -> bool:
        """True when this Database is currently talking to PostgreSQL."""
        return self.engine.dialect.name == "postgresql"

    def _raw_dbapi(self) -> Any:
        """
        Return the underlying DBAPI connection (sqlite3.Connection,
        psycopg2 connection, ...) for the handful of operations
        SQLAlchemy Core has no portable equivalent for (PRAGMA,
        executescript). Only ever called on the SQLite path.
        """
        return self.connection.connection

    def close(self) -> None:
        """Safely close the database connection."""
        if self.connection:
            try:
                self.connection.close()
            except SQLAlchemyError:
                pass
            finally:
                self.connection = None
        if self.engine and self._dispose_engine_on_close:
            try:
                self.engine.dispose()
            except SQLAlchemyError:
                pass
            finally:
                self.engine = None

    def __enter__(self) -> "Database":
        """Context manager entry."""
        return self

    def __exit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        """Context manager exit – closes connection cleanly."""
        self.close()

    # ------------------------------------------------------------------
    # Schema initialisation
    #
    # NOTE: schema.sql and every _maybe_migrate_* helper below are still
    # SQLite-specific (AUTOINCREMENT, PRAGMA, sqlite_master...), exactly
    # as they were before this conversion. They're now guarded to only
    # run on the SQLite path. Creating/migrating the Postgres schema
    # (via SQLAlchemy Table objects + Alembic) is the next follow-up
    # piece of work, not something this pass silently papers over.
    # ------------------------------------------------------------------

    def _maybe_create_schema(self) -> None:
        """
        Create the schema if the database is empty (no tables yet).

        - SQLite: keeps using schema.sql + the self-healing migrations
          below, unchanged from before — this is what your existing
          coach_ai.db already went through, so it keeps working exactly
          as-is.
        - PostgreSQL: startup is validation-only. Alembic must have upgraded
          the database to the application head before a process can serve
          work. Runtime code never creates or mutates production schema.
        """
        if not self.is_sqlite:
            inspector = inspect(self.engine)
            tables = set(inspector.get_table_names())
            if "alembic_version" not in tables:
                raise RuntimeError(
                    "PostgreSQL schema is not managed by Alembic. Run "
                    "`python -m alembic upgrade head` before starting CoachAI."
                )
            revision = self.connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one_or_none()
            if revision != ALEMBIC_HEAD:
                raise RuntimeError(
                    f"PostgreSQL schema revision {revision!r} is not the required "
                    f"CoachAI revision {ALEMBIC_HEAD!r}. Run Alembic upgrade head."
                )
            return

        cursor = self.connection.execute(
            text("SELECT name FROM sqlite_master WHERE type='table' LIMIT 1")
        )
        if cursor.fetchone() is not None:
            self._maybe_migrate_started_at()
            self._maybe_migrate_failure_reason_check()
            self._maybe_create_google_tables()
            self._maybe_migrate_task_google_event_id()
            self._maybe_migrate_task_is_fixed_time()
            self._maybe_migrate_task_google_exported_at()
            self._maybe_migrate_task_is_break()
            self._maybe_migrate_task_timer_fields()
            self._maybe_migrate_task_pause_count()
            self._maybe_migrate_task_pause_duration_fields()
            self._maybe_migrate_task_timer_version()
            self._maybe_migrate_user_password_hash()
            self._maybe_create_smart_scheduling_schema()
            self._maybe_create_future_planning_schema()
            self._maybe_create_notification_schema()
            self._maybe_create_mobile_core_schema()
            return  # Schema already applied

        schema_path = Path(__file__).with_name(SCHEMA_FILE)
        if not schema_path.exists():
            raise FileNotFoundError(
                f"Schema file not found: {schema_path}"
            )

        with open(schema_path, "r", encoding="utf-8") as f:
            self._raw_dbapi().executescript(f.read())
        self.connection.commit()

        # schema.sql only has the columns baked in from day one
        # (is_fixed_time). Everything added later purely via migration
        # (google_event_id, google_exported_at, the Google Calendar
        # tables) still needs to run once here too, so a BRAND NEW
        # SQLite database ends up at the same final shape as one that
        # started earlier and migrated forward.
        self._maybe_migrate_started_at()
        self._maybe_migrate_failure_reason_check()
        self._maybe_create_google_tables()
        self._maybe_migrate_task_google_event_id()
        self._maybe_migrate_task_is_fixed_time()
        self._maybe_migrate_task_google_exported_at()
        self._maybe_migrate_task_is_break()
        self._maybe_migrate_task_timer_fields()
        self._maybe_migrate_task_pause_count()
        self._maybe_migrate_task_pause_duration_fields()
        self._maybe_migrate_task_timer_version()
        self._maybe_migrate_user_password_hash()
        self._maybe_create_smart_scheduling_schema()
        self._maybe_create_future_planning_schema()
        self._maybe_create_notification_schema()
        self._maybe_create_mobile_core_schema()

    def _maybe_create_smart_scheduling_schema(self) -> None:
        """Create the segmented scheduler schema and safely backfill valid rows.

        This intentionally has no migration ledger.  Every statement and the
        segment-0 insert are idempotent, so startup is restart-safe even after
        an interrupted run.  A legacy interval is copied only when both values
        parse and end is strictly after start; missing, partial, overnight, and
        otherwise invalid values remain identifiable on ``tasks`` and receive
        no fabricated segment.
        """
        raw = self._raw_dbapi()
        plan_columns = {row[1] for row in raw.execute("PRAGMA table_info(plans)").fetchall()}
        if "schedule_revision" not in plan_columns:
            raw.execute("ALTER TABLE plans ADD COLUMN schedule_revision INTEGER NOT NULL DEFAULT 0")

        raw.executescript("""
            CREATE TABLE IF NOT EXISTS task_segments (
                segment_id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                segment_index INTEGER NOT NULL CHECK (segment_index >= 0),
                scheduled_start TIME NOT NULL,
                scheduled_end TIME NOT NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                google_event_id TEXT,
                google_calendar_id TEXT,
                calendar_provenance TEXT NOT NULL DEFAULT 'legacy_unknown'
                    CHECK (calendar_provenance IN ('imported','coachai_created','legacy_unknown')),
                calendar_sync_status TEXT NOT NULL DEFAULT 'not_synced',
                calendar_sync_error TEXT,
                FOREIGN KEY (task_id) REFERENCES tasks(task_id) ON DELETE CASCADE,
                UNIQUE(task_id, segment_index),
                CHECK (scheduled_end > scheduled_start)
            );
            CREATE INDEX IF NOT EXISTS idx_task_segments_task_id ON task_segments(task_id);
            CREATE INDEX IF NOT EXISTS idx_task_segments_google_event ON task_segments(google_event_id);
            CREATE TRIGGER IF NOT EXISTS trg_task_segments_valid_time_insert
            BEFORE INSERT ON task_segments
            WHEN time(NEW.scheduled_start) IS NULL
              OR time(NEW.scheduled_end) IS NULL
              OR time(NEW.scheduled_start) >= time(NEW.scheduled_end)
              OR time(NEW.scheduled_end) > '23:59:59'
            BEGIN
                SELECT RAISE(ABORT, 'invalid same-day task segment');
            END;
            CREATE TRIGGER IF NOT EXISTS trg_task_segments_valid_time_update
            BEFORE UPDATE OF scheduled_start, scheduled_end ON task_segments
            WHEN time(NEW.scheduled_start) IS NULL
              OR time(NEW.scheduled_end) IS NULL
              OR time(NEW.scheduled_start) >= time(NEW.scheduled_end)
              OR time(NEW.scheduled_end) > '23:59:59'
            BEGIN
                SELECT RAISE(ABORT, 'invalid same-day task segment');
            END;

            CREATE TABLE IF NOT EXISTS schedule_operations (
                operation_id TEXT PRIMARY KEY,
                plan_id INTEGER NOT NULL,
                user_id TEXT NOT NULL,
                base_revision INTEGER NOT NULL,
                result_revision INTEGER NOT NULL,
                before_state TEXT NOT NULL,
                after_state TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'applied' CHECK (status IN ('applied','undone')),
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                undone_at DATETIME,
                calendar_reconciled_at DATETIME,
                calendar_reconciliation_error TEXT,
                FOREIGN KEY (plan_id) REFERENCES plans(plan_id) ON DELETE CASCADE,
                FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
            );
            CREATE INDEX IF NOT EXISTS idx_schedule_operations_plan
                ON schedule_operations(plan_id, created_at);
            CREATE INDEX IF NOT EXISTS idx_schedule_operations_user
                ON schedule_operations(user_id);
        """)
        operation_columns = {
            row[1] for row in raw.execute("PRAGMA table_info(schedule_operations)").fetchall()
        }
        if "calendar_reconciled_at" not in operation_columns:
            raw.execute("ALTER TABLE schedule_operations ADD COLUMN calendar_reconciled_at DATETIME")
        if "calendar_reconciliation_error" not in operation_columns:
            raw.execute("ALTER TABLE schedule_operations ADD COLUMN calendar_reconciliation_error TEXT")

        rows = raw.execute("""
            SELECT task_id, scheduled_start, scheduled_end, google_event_id
            FROM tasks
            WHERE scheduled_start IS NOT NULL AND scheduled_end IS NOT NULL
        """).fetchall()
        for task_id, start_value, end_value, google_event_id in rows:
            start = self._parse_legacy_time(start_value)
            end = self._parse_legacy_time(end_value)
            if start is None or end is None or end <= start:
                continue
            raw.execute("""
                INSERT OR IGNORE INTO task_segments
                    (task_id, segment_index, scheduled_start, scheduled_end,
                     google_event_id, calendar_provenance)
                VALUES (?, 0, ?, ?, ?, 'legacy_unknown')
            """, (task_id, start.strftime("%H:%M:%S"), end.strftime("%H:%M:%S"), google_event_id))
        raw.commit()

    def _maybe_create_future_planning_schema(self) -> None:
        """Add restart-safe future-planning metadata without rewriting data."""
        raw = self._raw_dbapi()
        task_columns = {row[1] for row in raw.execute("PRAGMA table_info(tasks)").fetchall()}
        if "earliest_start" not in task_columns:
            raw.execute("ALTER TABLE tasks ADD COLUMN earliest_start DATETIME")
        if "deadline" not in task_columns:
            raw.execute("ALTER TABLE tasks ADD COLUMN deadline DATETIME")
        segment_columns = {
            row[1] for row in raw.execute("PRAGMA table_info(task_segments)").fetchall()
        }
        if "scheduled_date" not in segment_columns:
            raw.execute("ALTER TABLE task_segments ADD COLUMN scheduled_date DATE")
        # Existing same-day segments inherit their logical task's plan date.
        # The predicate makes this safe to resume after interruption.
        raw.execute("""
            UPDATE task_segments
               SET scheduled_date = (
                   SELECT p.plan_date
                     FROM tasks t JOIN plans p ON p.plan_id=t.plan_id
                    WHERE t.task_id=task_segments.task_id
               )
             WHERE scheduled_date IS NULL
        """)
        raw.execute(
            "CREATE INDEX IF NOT EXISTS idx_task_segments_date ON task_segments(scheduled_date)"
        )
        raw.commit()

    def _maybe_create_notification_schema(self) -> None:
        """Create Patch 1 notification tables additively on SQLite.

        There is no historical notification schema to rewrite or backfill.
        ``checkfirst`` makes startup idempotent, and using the same SQLAlchemy
        definitions as fresh PostgreSQL prevents a second divergent schema.
        No Firebase/APNs calls or fabricated notification history occur here.
        """
        metadata.create_all(
            bind=self.connection,
            tables=[
                notification_preferences,
                notification_type_preferences,
                notification_devices,
                notification_outbox,
                notifications,
                notification_deliveries,
                notification_local_schedules,
                notification_sync_state,
                task_notification_state,
            ],
            checkfirst=True,
        )
        raw = self._raw_dbapi()
        outbox_columns = {row[1] for row in raw.execute("PRAGMA table_info(notification_outbox)").fetchall()}
        if "locked_by" not in outbox_columns:
            raw.execute("ALTER TABLE notification_outbox ADD COLUMN locked_by TEXT")
        if "expires_at" not in outbox_columns:
            raw.execute("ALTER TABLE notification_outbox ADD COLUMN expires_at DATETIME")
        notification_columns = {row[1] for row in raw.execute("PRAGMA table_info(notifications)").fetchall()}
        if "sync_revision" not in notification_columns:
            raw.execute("ALTER TABLE notifications ADD COLUMN sync_revision INTEGER NOT NULL DEFAULT 0")
        device_columns = {row[1] for row in raw.execute("PRAGMA table_info(notification_devices)").fetchall()}
        if "app_identifier" not in device_columns:
            raw.execute("ALTER TABLE notification_devices ADD COLUMN app_identifier TEXT")
        if "provider_metadata" not in device_columns:
            raw.execute("ALTER TABLE notification_devices ADD COLUMN provider_metadata TEXT NOT NULL DEFAULT '{}'")
        delivery_columns = {row[1] for row in raw.execute("PRAGMA table_info(notification_deliveries)").fetchall()}
        for column, definition in (
            ("provider", "TEXT"),
            ("next_attempt_at", "DATETIME"),
            ("last_attempt_at", "DATETIME"),
            ("accepted_at", "DATETIME"),
            ("locked_at", "DATETIME"),
            ("locked_by", "TEXT"),
            ("error_classification", "TEXT"),
        ):
            if column not in delivery_columns:
                raw.execute(f"ALTER TABLE notification_deliveries ADD COLUMN {column} {definition}")
        raw.execute("UPDATE notification_deliveries SET next_attempt_at=COALESCE(next_attempt_at,queued_at,CURRENT_TIMESTAMP)")
        raw.execute("CREATE INDEX IF NOT EXISTS idx_notification_outbox_lease ON notification_outbox(status,locked_at)")
        raw.execute("CREATE INDEX IF NOT EXISTS idx_notifications_sync ON notifications(user_id,sync_revision)")
        raw.execute("CREATE INDEX IF NOT EXISTS idx_notification_deliveries_push_ready ON notification_deliveries(channel,status,next_attempt_at)")
        raw.execute("CREATE INDEX IF NOT EXISTS idx_notification_deliveries_lease ON notification_deliveries(channel,locked_at)")
        self.connection.commit()

    @staticmethod
    def _parse_legacy_time(value: Any):
        if value is None:
            return None
        candidate = str(value).strip()
        for fmt in ("%H:%M:%S", "%H:%M"):
            try:
                return datetime.strptime(candidate, fmt).time()
            except ValueError:
                pass
        return None

    def _maybe_migrate_user_password_hash(self) -> None:
        """Add ``password_hash`` column to ``users`` if missing."""
        cursor = self.connection.execute(text("PRAGMA table_info(users)"))
        existing_columns = {row[1] for row in cursor.fetchall()}
        if "password_hash" not in existing_columns:
            self.connection.execute(
                text("ALTER TABLE users ADD COLUMN password_hash TEXT")
            )
            self.connection.commit()

    def _maybe_migrate_started_at(self) -> None:
        """
        Add the ``started_at`` column to an existing ``tasks`` table if it
        doesn't have it yet. SQLite-only self-healing migration.
        """
        cursor = self.connection.execute(text("PRAGMA table_info(tasks)"))
        existing_columns = {row[1] for row in cursor.fetchall()}
        if "started_at" not in existing_columns:
            self.connection.execute(
                text("ALTER TABLE tasks ADD COLUMN started_at DATETIME")
            )
            self.connection.commit()

    def _maybe_migrate_failure_reason_check(self) -> None:
        """
        Rebuild the ``tasks`` table if its ``failure_reason`` CHECK
        constraint doesn't yet include ``'Ran out of time'``. SQLite-only
        (Postgres CHECK constraints CAN be altered in place with ALTER
        TABLE ... DROP/ADD CONSTRAINT, so this rebuild dance won't be
        needed there at all).

        IMPORTANT: this used to hard-code the column list as it existed
        the day this migration was written, so on any database where
        other migrations (is_fixed_time, is_break, timer fields,
        google_event_id, google_exported_at, ...) had already run, this
        rebuild silently dropped every one of those columns and their
        data. It now discovers the table's CURRENT full set of columns
        at runtime and carries every single one of them over untouched,
        only editing the ``failure_reason`` CHECK's allowed-value list
        in place. It is also idempotent: the early-return above already
        makes a second run a no-op, and the CREATE TABLE below always
        reflects whatever columns exist right now, so it's safe however
        many times it runs or in whatever order relative to the other
        migrations.
        """
        cursor = self.connection.execute(
            text("SELECT sql FROM sqlite_master WHERE type='table' AND name='tasks'")
        )
        row = cursor.fetchone()
        if row is None or row[0] is None or "Ran out of time" in row[0]:
            return  # Already up to date, or table doesn't exist yet.

        old_ddl: str = row[0]

        # Discover every column that exists on the table RIGHT NOW,
        # whatever earlier/later migrations have already added, instead
        # of assuming a fixed, potentially stale list.
        info_rows = self.connection.execute(text("PRAGMA table_info(tasks)")).fetchall()
        if not info_rows:
            return
        columns = [info_row[1] for info_row in info_rows]
        col_list_sql = ", ".join(columns)

        # Only rewrite the failure_reason CHECK's allowed-value list.
        # Every other column definition, type, default, other CHECK
        # constraint, and foreign key in the original DDL text is
        # carried over completely unchanged.
        new_ddl, n_subs = re.subn(
            r"CHECK\s*\(\s*failure_reason\s+IN\s*\([^)]*\)\s*\)",
            "CHECK (failure_reason IN ("
            "'Harder than expected','Distracted','Tired',"
            "'Unexpected event','Changed priorities','Ran out of time'))",
            old_ddl,
            count=1,
            flags=re.IGNORECASE,
        )
        if n_subs == 0:
            # Defensive: the constraint didn't look like we expected.
            # Don't blindly rebuild the table and risk losing data --
            # bail out safely and leave the table as-is.
            return

        new_ddl = re.sub(
            r"CREATE TABLE\s+tasks\b", "CREATE TABLE tasks_new", new_ddl, count=1, flags=re.IGNORECASE
        )

        raw = self._raw_dbapi()
        raw.execute("PRAGMA foreign_keys = OFF")
        try:
            raw.execute("BEGIN")
            raw.execute(new_ddl)
            raw.execute(
                f"INSERT INTO tasks_new ({col_list_sql}) "
                f"SELECT {col_list_sql} FROM tasks"
            )
            raw.execute("DROP TABLE tasks")
            raw.execute("ALTER TABLE tasks_new RENAME TO tasks")
            raw.execute("CREATE INDEX IF NOT EXISTS idx_tasks_plan_id ON tasks(plan_id)")
            raw.execute("CREATE INDEX IF NOT EXISTS idx_tasks_category_id ON tasks(category_id)")
            raw.execute("CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status)")
            raw.execute("CREATE INDEX IF NOT EXISTS idx_tasks_plan_status ON tasks(plan_id, status)")
            raw.commit()
        except Exception:
            raw.rollback()
            raise
        finally:
            raw.execute("PRAGMA foreign_keys = ON")

    def _maybe_create_google_tables(self) -> None:
        """Create Google Calendar tables if they don't exist (idempotent)."""
        self._raw_dbapi().executescript("""
            CREATE TABLE IF NOT EXISTS google_oauth_tokens (
                token_id      INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id       INTEGER NOT NULL UNIQUE,
                access_token  TEXT NOT NULL,
                refresh_token TEXT NOT NULL,
                token_expiry  DATETIME NOT NULL,
                scopes        TEXT NOT NULL DEFAULT '',
                created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS google_selected_calendars (
                selection_id  INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id       INTEGER NOT NULL,
                calendar_id   TEXT NOT NULL,
                calendar_name TEXT NOT NULL DEFAULT '',
                color         TEXT NOT NULL DEFAULT '#4285F4',
                is_primary    INTEGER NOT NULL DEFAULT 0,
                created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
                UNIQUE(user_id, calendar_id)
            );

            CREATE TABLE IF NOT EXISTS google_calendar_events (
                event_id        INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id         INTEGER NOT NULL,
                google_event_id TEXT NOT NULL,
                title           TEXT NOT NULL DEFAULT '',
                start_time      TIME NOT NULL,
                end_time        TIME NOT NULL,
                event_date      DATE NOT NULL,
                calendar_id     TEXT NOT NULL,
                last_synced_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
                UNIQUE(user_id, google_event_id, event_date)
            );

            CREATE INDEX IF NOT EXISTS idx_gcal_events_user_date
                ON google_calendar_events(user_id, event_date);
            CREATE INDEX IF NOT EXISTS idx_gcal_selected_user
                ON google_selected_calendars(user_id);
        """)
        self.connection.commit()

    def _maybe_migrate_task_google_event_id(self) -> None:
        """Add google_event_id column to tasks if missing."""
        cursor = self.connection.execute(text("PRAGMA table_info(tasks)"))
        existing_columns = {row[1] for row in cursor.fetchall()}
        if "google_event_id" not in existing_columns:
            self.connection.execute(
                text("ALTER TABLE tasks ADD COLUMN google_event_id TEXT")
            )
            self.connection.commit()

    def _maybe_migrate_task_is_fixed_time(self) -> None:
        """
        Add the ``is_fixed_time`` column to an existing ``tasks`` table if
        it doesn't have it yet. This is what lets the app tell a task the
        AI/user pinned to a real clock time (e.g. a fixed 2:00 PM meeting)
        apart from a task the Scheduler is free to move — without it,
        every task looked the same once scheduled_start was set, whether
        it was fixed on purpose or just previously auto-scheduled.

        Existing rows default to 0 (flexible), since there is no reliable
        way to infer which already-scheduled tasks were originally meant
        to be fixed.
        """
        cursor = self.connection.execute(text("PRAGMA table_info(tasks)"))
        existing_columns = {row[1] for row in cursor.fetchall()}
        if "is_fixed_time" not in existing_columns:
            self.connection.execute(
                text(
                    "ALTER TABLE tasks ADD COLUMN is_fixed_time "
                    "INTEGER NOT NULL DEFAULT 0"
                )
            )
            self.connection.commit()

    def _maybe_migrate_task_google_exported_at(self) -> None:
        """
        Add the ``google_exported_at`` column to an existing ``tasks``
        table if it doesn't have it yet. This records when a task was
        last pushed to Google Calendar, so the app can tell a stale
        export (task rescheduled after it was exported) apart from one
        that's already in sync — without it, there was no way to warn
        the user that Google Calendar still holds an old time.
        """
        cursor = self.connection.execute(text("PRAGMA table_info(tasks)"))
        existing_columns = {row[1] for row in cursor.fetchall()}
        if "google_exported_at" not in existing_columns:
            self.connection.execute(
                text("ALTER TABLE tasks ADD COLUMN google_exported_at DATETIME")
            )
            self.connection.commit()

    def _maybe_migrate_task_is_break(self) -> None:
        """
        Add the ``is_break`` column to an existing ``tasks`` table if it
        doesn't have it yet. This is what turns a break from a purely
        in-memory scheduling hint (gone the moment the page reloads)
        into a real, persisted task row — one the Scheduler already
        treats as an immovable fixed-time block (via is_fixed_time),
        and that the UI can show in the task list, start a timer on,
        and mark completed, exactly like any other task.

        Existing rows default to 0 (not a break), since there is no
        reliable way to infer which already-scheduled tasks were
        originally meant to represent a break.
        """
        cursor = self.connection.execute(text("PRAGMA table_info(tasks)"))
        existing_columns = {row[1] for row in cursor.fetchall()}
        if "is_break" not in existing_columns:
            self.connection.execute(
                text(
                    "ALTER TABLE tasks ADD COLUMN is_break "
                    "INTEGER NOT NULL DEFAULT 0"
                )
            )
            self.connection.commit()

    def _maybe_migrate_task_timer_fields(self) -> None:
        """
        Add ``timer_accumulated_seconds`` and ``timer_segment_started_at``
        to an existing ``tasks`` table if missing. Together these let a
        task's timer be paused and resumed without losing accuracy:

        - timer_accumulated_seconds: total active seconds banked from
          every PREVIOUS run segment (i.e. excludes any time currently
          paused, and excludes the segment still in progress).
        - timer_segment_started_at: when the CURRENT active segment
          began. NULL means the timer is currently paused (or the task
          was never started) — there's deliberately no separate
          "paused" status; a task stays 'in_progress' the whole time,
          and pause/resume just toggles whether a segment is running.

        Existing rows default to 0 / NULL, which is exactly correct for
        a task that predates this feature (no time banked, no segment
        running) — it behaves as if freshly started once someone
        presses Start.
        """
        cursor = self.connection.execute(text("PRAGMA table_info(tasks)"))
        existing_columns = {row[1] for row in cursor.fetchall()}
        if "timer_accumulated_seconds" not in existing_columns:
            self.connection.execute(
                text(
                    "ALTER TABLE tasks ADD COLUMN timer_accumulated_seconds "
                    "INTEGER NOT NULL DEFAULT 0"
                )
            )
            self.connection.commit()
        if "timer_segment_started_at" not in existing_columns:
            self.connection.execute(
                text("ALTER TABLE tasks ADD COLUMN timer_segment_started_at DATETIME")
            )
            self.connection.commit()

    def _maybe_migrate_task_pause_count(self) -> None:
        """
        Add the ``pause_count`` column to an existing ``tasks`` table if
        missing. Incremented once per pause (see
        Database.increment_task_pause_count / services.pause_task_timer)
        and read by get_pause_matrix() to build the Category × Time-of-
        day focus matrix — a per-task counter, not derived from the
        timer segment fields, since those only track total banked time,
        not how many times a task was interrupted.
        """
        cursor = self.connection.execute(text("PRAGMA table_info(tasks)"))
        existing_columns = {row[1] for row in cursor.fetchall()}
        if "pause_count" not in existing_columns:
            self.connection.execute(
                text("ALTER TABLE tasks ADD COLUMN pause_count INTEGER NOT NULL DEFAULT 0")
            )
            self.connection.commit()

    def _maybe_migrate_task_pause_duration_fields(self) -> None:
        """
        Add ``paused_at`` and ``timer_total_paused_seconds`` to an
        existing ``tasks`` table if missing. pause_count alone only says
        HOW MANY TIMES a task was interrupted — these two add HOW LONG
        it stayed paused each time, which matters a lot for analysis:
        five short 30-second pauses (a bit of fidgeting) reads very
        differently from one 40-minute pause (a real interruption or
        context switch), even though pause_count is 5 either way.

        - paused_at: when the CURRENT pause began. NULL whenever the
          timer isn't paused right now (running, not yet started, or
          already finished).
        - timer_total_paused_seconds: cumulative total of every FINISHED
          pause's duration for this task (the currently-open pause, if
          any, is added on top of this at read time — see
          services.get_task_paused_seconds — not stored until it ends).
        """
        cursor = self.connection.execute(text("PRAGMA table_info(tasks)"))
        existing_columns = {row[1] for row in cursor.fetchall()}
        if "paused_at" not in existing_columns:
            self.connection.execute(
                text("ALTER TABLE tasks ADD COLUMN paused_at DATETIME")
            )
            self.connection.commit()
        if "timer_total_paused_seconds" not in existing_columns:
            self.connection.execute(
                text(
                    "ALTER TABLE tasks ADD COLUMN timer_total_paused_seconds "
                    "INTEGER NOT NULL DEFAULT 0"
                )
            )
            self.connection.commit()

    def _maybe_migrate_task_timer_version(self) -> None:
        """
        Add ``timer_version`` to an existing ``tasks`` table if missing.

        An optimistic-concurrency counter bumped by every
        set_task_timer_state() write. start/pause/resume/finish all
        follow a read-current-state -> compute-new-state -> write
        pattern; without a version check, two overlapping calls (e.g.
        a double-submitted Pause click, or two open tabs) can both read
        the same starting state and the second write silently clobbers
        the first, losing whatever elapsed time the first write banked.
        Passing the version read alongside the rest of the timer state
        to set_task_timer_state() makes the write conditional: it only
        applies if nothing else has updated the row in between, and
        raises TimerConflictError otherwise so the caller can retry
        against fresh state instead of silently losing time.

        Existing rows default to 0, which is exactly correct -- there's
        no prior state to be racing against yet.
        """
        cursor = self.connection.execute(text("PRAGMA table_info(tasks)"))
        existing_columns = {row[1] for row in cursor.fetchall()}
        if "timer_version" not in existing_columns:
            self.connection.execute(
                text("ALTER TABLE tasks ADD COLUMN timer_version INTEGER NOT NULL DEFAULT 0")
            )
            self.connection.commit()

    def _maybe_create_mobile_core_schema(self) -> None:
        """Bring explicit-test/legacy SQLite schemas to the mobile-core shape.

        Production PostgreSQL is migrated with Alembic.  This compatibility
        path remains intentionally SQLite-only so historical local files and
        isolated tests keep working without becoming the production migration
        mechanism.
        """
        if not self.is_sqlite:
            return
        user_columns = {
            row[1] for row in self.connection.execute(text("PRAGMA table_info(users)")).fetchall()
        }
        if "password_hash" not in user_columns:
            self.connection.execute(text("ALTER TABLE users ADD COLUMN password_hash TEXT"))
        if "is_disabled" not in user_columns:
            self.connection.execute(
                text("ALTER TABLE users ADD COLUMN is_disabled INTEGER NOT NULL DEFAULT 0")
            )
        if "updated_at" not in user_columns:
            # SQLite cannot add a column with a non-constant CURRENT_TIMESTAMP
            # default. Historical files receive a nullable compatibility
            # column; fresh schemas and PostgreSQL retain the canonical
            # non-null/default constraint.
            self.connection.execute(text("ALTER TABLE users ADD COLUMN updated_at DATETIME"))
            self.connection.execute(text("UPDATE users SET updated_at=CURRENT_TIMESTAMP"))
        task_columns = {
            row[1] for row in self.connection.execute(text("PRAGMA table_info(tasks)")).fetchall()
        }
        if "version" not in task_columns:
            self.connection.execute(
                text("ALTER TABLE tasks ADD COLUMN version INTEGER NOT NULL DEFAULT 0")
            )
        self.connection.commit()
        metadata.create_all(self.engine)

    # ------------------------------------------------------------------
    # Low-level query helpers
    #
    # These are the ONLY methods that changed behind the scenes. Every
    # domain method below (create_user, get_tasks_by_plan, ...) still
    # calls self.execute(sql, params) / self.fetch_one(...) / etc. with
    # the exact same "?"-style SQL as before — nothing below this point
    # needed to change at all.
    # ------------------------------------------------------------------

    @staticmethod
    def _to_named(sql: str, parameters: tuple[Any, ...]) -> tuple[Any, dict[str, Any]]:
        """
        Translate sqlite3-style positional "?" placeholders (and a plain
        tuple of values) into SQLAlchemy's dialect-agnostic named-param
        style, so the exact same SQL string works against SQLite or
        Postgres without every call site needing to change.

        This walks the SQL character-by-character and only treats a
        bare ``?`` as a placeholder when it is OUTSIDE a quoted string
        literal (single-quoted ``'...'``, double-quoted ``"..."``, since
        SQLite allows either for string literals). A literal ``?``
        inside a quoted string (e.g. ``WHERE title = 'Done?'``) is left
        untouched and passed straight through instead of being mistaken
        for a bind parameter. A doubled quote (``''`` / `""`) inside a
        literal — the standard SQL escape for a literal quote character
        — is handled so it doesn't prematurely end the string.
        """
        if not parameters:
            return text(sql), {}

        counter = count(1)
        param_names: list[str] = []
        out: list[str] = []

        i = 0
        n = len(sql)
        quote_char: Optional[str] = None
        while i < n:
            ch = sql[i]
            if quote_char is not None:
                out.append(ch)
                if ch == quote_char:
                    # A doubled quote is an escaped literal quote, not
                    # the end of the string -- consume both characters
                    # and stay inside the string.
                    if i + 1 < n and sql[i + 1] == quote_char:
                        out.append(sql[i + 1])
                        i += 2
                        continue
                    quote_char = None
                i += 1
                continue

            if ch in ("'", '"'):
                quote_char = ch
                out.append(ch)
                i += 1
                continue

            if ch == "?":
                name = f"p{next(counter)}"
                param_names.append(name)
                out.append(f":{name}")
                i += 1
                continue

            out.append(ch)
            i += 1

        named_sql = "".join(out)
        param_dict = {name: value for name, value in zip(param_names, parameters)}
        return text(named_sql), param_dict

    def execute(
        self,
        sql: str,
        parameters: tuple[Any, ...] = (),
    ) -> CursorResult:
        """
        Execute a single SQL statement with parameters.

        Args:
            sql: Parameterised SQL statement (sqlite3-style "?" placeholders).
            parameters: Values to bind (never use string formatting).

        Returns:
            SQLAlchemy CursorResult for further inspection (supports
            .fetchone() and .fetchall()).

        Raises:
            SQLAlchemyError: On execution failure.
        """
        stmt, params = self._to_named(sql, parameters)
        try:
            return self.connection.execute(stmt, params)
        except DBAPIError as exc:
            if exc.connection_invalidated:
                # This Database holds ONE connection for its whole
                # lifetime (see class docstring / Issue 7 notes) rather
                # than checking one out per call, so SQLAlchemy's usual
                # pool-level pool_pre_ping/pool_recycle never gets a
                # chance to catch a connection that died from under us
                # (e.g. a Postgres server-side idle timeout, or a
                # dropped network connection on a long-lived thread).
                # SQLAlchemy has already marked the connection invalid
                # at this point; reconnect once and retry this one
                # statement rather than leaving every future call on
                # this thread broken until the process restarts.
                if self._transaction_depth > 0:
                    # Retrying only this statement would silently discard all
                    # earlier statements from the invalidated transaction and
                    # could commit a partial schedule/Undo.  The transaction
                    # context will roll back and reconnect after aborting the
                    # complete unit of work.
                    self._transaction_failed = True
                    raise
                self._reconnect_after_invalidation()
                return self.connection.execute(stmt, params)
            if self._transaction_depth > 0:
                self._transaction_failed = True
            self.connection.rollback()
            raise
        except SQLAlchemyError as exc:
            if self._transaction_depth > 0:
                self._transaction_failed = True
            self.connection.rollback()
            raise exc

    def fetch_one(
        self,
        sql: str,
        parameters: tuple[Any, ...] = (),
    ) -> Optional[dict]:
        """
        Execute a query and return the first row as a dict, or ``None``.

        Args:
            sql: Parameterised SELECT statement.
            parameters: Values to bind.

        Returns:
            First matching row as a dict (supports both row["col"] and
            row.get("col")), or ``None`` if no results.
        """
        cursor = self.execute(sql, parameters)
        row = cursor.fetchone()
        return dict(row._mapping) if row is not None else None

    def fetch_all(
        self,
        sql: str,
        parameters: tuple[Any, ...] = (),
    ) -> list[dict]:
        """
        Execute a query and return all rows as dicts.

        Args:
            sql: Parameterised SELECT statement.
            parameters: Values to bind.

        Returns:
            List of matching rows as dicts (empty list if none).
        """
        cursor = self.execute(sql, parameters)
        return [dict(row._mapping) for row in cursor.fetchall()]

    def commit(self) -> None:
        """Commit the current transaction."""
        # Public mutation helpers call commit() for standalone use.  When one
        # of those helpers participates in a larger domain transaction, the
        # outer transaction must remain the only commit boundary.
        if self._transaction_depth == 0:
            self.connection.commit()

    def _insert_and_get_id(
        self,
        sql: str,
        parameters: tuple[Any, ...],
        pk_column: str,
    ) -> Optional[int]:
        """
        Execute an INSERT (or upsert) and return the affected row's
        primary key, portably across SQLite and Postgres.

        SQLite (and MySQL) support ``cursor.lastrowid`` directly, but
        Postgres does not reliably support it at all — the correct,
        dialect-agnostic way to get an inserted id back is
        ``INSERT ... RETURNING <pk_column>``, which both SQLite (3.35+)
        and Postgres understand. Using this everywhere means every
        ``create_*``/``add_*`` method below returns the right id on
        either database with no per-call-site branching.

        Args:
            sql: The INSERT statement, WITHOUT a trailing semicolon or
                RETURNING clause (both are added here).
            parameters: Values to bind, sqlite3-style ``?`` order.
            pk_column: Name of the primary-key column to return.

        Returns:
            The primary key of the inserted/updated row, or ``None`` if
            the statement affected no row (e.g. an ON CONFLICT DO
            NOTHING that matched nothing).
        """
        stmt = sql.rstrip().rstrip(";") + f" RETURNING {pk_column}"
        cursor = self.execute(stmt, parameters)
        row = cursor.fetchone()
        return row[0] if row is not None else None

    # ------------------------------------------------------------------
    # Transaction context manager
    # ------------------------------------------------------------------

    @contextmanager
    def transaction(self):
        """
        Context manager for explicit transactions.

        Usage::
            with db.transaction():
                db.execute("INSERT ...", (value,))
                db.execute("UPDATE ...", (value,))
            # Committed on success, rolled back on exception.
        """
        outermost = self._transaction_depth == 0
        if outermost:
            self._transaction_failed = False
        self._transaction_depth += 1
        try:
            yield
        except Exception:
            self._transaction_failed = True
            raise
        finally:
            self._transaction_depth -= 1
            if outermost:
                try:
                    if self._transaction_failed:
                        self.connection.rollback()
                    else:
                        self.connection.commit()
                finally:
                    invalidated = bool(
                        self.connection is None
                        or getattr(self.connection, "invalidated", False)
                        or getattr(self.connection, "closed", False)
                    )
                    self._transaction_failed = False
                    if invalidated:
                        self._reconnect_after_invalidation()

    def _enqueue_notification_event_no_commit(
        self,
        *,
        user_id: str,
        event_type: str,
        payload: Optional[dict] = None,
        task_id: Optional[int] = None,
        segment_id: Optional[int] = None,
        scheduled_date: Optional[date | str] = None,
        segment_index: Optional[int] = None,
        schedule_revision: Optional[int] = None,
        schedule_operation_id: Optional[str] = None,
        idempotency_key: Optional[str] = None,
        bump_sync_revision: bool = False,
    ) -> tuple[str, bool, int]:
        """Insert one provider-independent event on this transaction.

        The local imports keep ``database`` independent from notification
        policy/delivery modules at import time while still sharing this exact
        connection and rollback boundary.
        """
        from notifications.domain import DomainEvent
        from notifications.repository import NotificationRepository

        event = DomainEvent.create(
            user_id=user_id,
            event_type=event_type,
            payload=payload,
            task_id=task_id,
            segment_id=segment_id,
            scheduled_date=scheduled_date,
            segment_index=segment_index,
            schedule_revision=schedule_revision,
            schedule_operation_id=schedule_operation_id,
            idempotency_key=idempotency_key,
        )
        return NotificationRepository(self).enqueue_event(
            event, bump_sync_revision=bump_sync_revision,
        )

    @staticmethod
    def _schedule_notification_payload(before_state: dict, after_state: dict) -> dict:
        """Summarize one schedule operation without producing per-task events."""
        def windows(state: dict) -> dict[int, tuple]:
            grouped: dict[int, list[tuple]] = {}
            for segment in state.get("segments", []):
                task_id = int(segment["task_id"])
                grouped.setdefault(task_id, []).append((
                    str(segment.get("scheduled_date") or segment.get("effective_date") or ""),
                    int(segment.get("segment_index") or 0),
                    str(segment.get("scheduled_start") or ""),
                    str(segment.get("scheduled_end") or ""),
                ))
            return {task_id: tuple(sorted(items)) for task_id, items in grouped.items()}

        before = windows(before_state)
        after = windows(after_state)
        created = {int(task_id) for task_id in before_state.get("created_task_ids", [])}
        task_ids = sorted(set(before) | set(after) | created)
        adjusted = [task_id for task_id in task_ids if before.get(task_id) != after.get(task_id)]
        unplaced = [
            task_id for task_id in task_ids
            if (before.get(task_id) or task_id in created) and not after.get(task_id)
        ]
        return {
            "adjusted_count": len(adjusted),
            "adjusted_task_ids": adjusted,
            "unplaced_count": len(unplaced),
            "unplaced_task_ids": unplaced,
            "batched": True,
            "actions": {"view_changes": True, "undo": True},
        }

    def _reconnect_after_invalidation(self) -> None:
        """Replace an unusable connection outside an explicit transaction."""
        if self.connection is not None:
            try:
                self.connection.close()
            except SQLAlchemyError:
                pass
        if self.engine is not None and self._dispose_engine_on_close:
            try:
                self.engine.dispose()
            except SQLAlchemyError:
                pass
        self.connection = None
        if self._dispose_engine_on_close:
            self.engine = None
        # Schema was already validated when this Database instance started;
        # reconnecting after a network failure must not run migration checks
        # inside application work.
        previous_check = self._check_schema
        self._check_schema = False
        try:
            self._connect()
        finally:
            self._check_schema = previous_check

    # =====================================================================
    # USERS
    # =====================================================================

    def create_user(
        self,
        user_id: str,
        email: str,
        display_name: str,
        timezone: str = "UTC",
        password_hash: Optional[str] = None,
    ) -> Any:
        """
        Register a new user and initialise an empty profile.

        Current databases use the caller-provided text identity. Historical
        CoachAI SQLite databases used an auto-increment integer identity and
        a required password column; those files are supported without a
        destructive schema rewrite.

        Args:
            user_id: Preferred external/text identity for this user.
            email: Unique email address.
            display_name: Human-readable name.
            timezone: IANA timezone name (default ``UTC``).
            password_hash: Optional hash for local email/password login.

        Returns:
            The inserted identity. Historical SQLite schemas return their
            generated integer identity; current schemas return ``user_id``.

        Raises:
            sqlite3.IntegrityError: If the user_id or email already exists.
        """
        columns = {
            column["name"]: column for column in inspect(self.engine).get_columns("users")
        }
        user_id_type = str(columns["user_id"]["type"]).upper()
        legacy_integer_identity = self.is_sqlite and "INT" in user_id_type
        password_column = columns.get("password_hash")

        with self.transaction():
            if legacy_integer_identity:
                if password_column is not None:
                    # This marker is deliberately not a valid CoachAI password
                    # hash, so a guest/Firebase-only identity cannot use local
                    # email/password authentication.
                    stored_hash = password_hash if password_hash is not None else "!"
                    actual_user_id = self._insert_and_get_id(
                        """
                        INSERT INTO users (email, password_hash, display_name, timezone)
                        VALUES (?, ?, ?, ?)
                        """,
                        (email, stored_hash, display_name, timezone),
                        "user_id",
                    )
                else:
                    actual_user_id = self._insert_and_get_id(
                        """
                        INSERT INTO users (email, display_name, timezone)
                        VALUES (?, ?, ?)
                        """,
                        (email, display_name, timezone),
                        "user_id",
                    )
            else:
                actual_user_id = user_id
                if password_column is not None and password_hash is not None:
                    self.execute(
                        """
                        INSERT INTO users
                            (user_id, email, display_name, timezone, password_hash)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        (actual_user_id, email, display_name, timezone, password_hash),
                    )
                else:
                    self.execute(
                        """
                        INSERT INTO users (user_id, email, display_name, timezone)
                        VALUES (?, ?, ?, ?)
                        """,
                        (actual_user_id, email, display_name, timezone),
                    )

            # Initialise empty profile so AI always has a row to read
            self.execute(
                "INSERT INTO user_profiles (user_id) VALUES (?)",
                (actual_user_id,),
            )

        return actual_user_id

    def get_or_create_user(
        self,
        user_id: str,
        email: str,
        display_name: str,
        timezone: str = "UTC",
    ) -> sqlite3.Row:
        """
        Fetch the user row for a Firebase uid, creating it (idempotently)
        on first login.

        Call this right after verifying a Firebase ID token, every time
        — not just on first sign-up — so a user's row always exists
        before any other query needs it. Safe to call repeatedly: if
        the user already exists, this is a single read and no write.

        Args:
            user_id: The Firebase uid for this user.
            email: Email from the verified Firebase token.
            display_name: Display name from the verified Firebase token.
            timezone: IANA timezone name, used only on first creation.

        Returns:
            The user row (freshly created or pre-existing).
        """
        existing = self.get_user(user_id)
        if existing is not None:
            return existing
        try:
            created_user_id = self.create_user(
                user_id=user_id,
                email=email,
                display_name=display_name,
                timezone=timezone,
            )
        except IntegrityError:
            # Several mobile screens load concurrently after sign-in. Another
            # request may have provisioned this same verified Firebase uid
            # between the read above and the insert. The unique key is the
            # authority here; after its transaction commits, reuse that row.
            concurrent = self.get_user(user_id)
            if concurrent is not None:
                return concurrent
            raise
        return self.get_user(created_user_id)

    def get_user(self, user_id: str) -> Optional[sqlite3.Row]:
        """
        Retrieve a user by ID.

        Args:
            user_id: Primary key of the user (the Firebase uid).

        Returns:
            User row, or ``None`` if not found.
        """
        return self.fetch_one(
            "SELECT * FROM users WHERE user_id = ?",
            (user_id,),
        )

    def get_user_by_email(self, email: str) -> Optional[sqlite3.Row]:
        """
        Retrieve a user by email address.

        Args:
            email: Email to look up.

        Returns:
            User row, or ``None`` if not found.
        """
        return self.fetch_one(
            "SELECT * FROM users WHERE email = ?",
            (email,),
        )

    def get_all_user_ids(self) -> list[str]:
        """
        Retrieve every user_id in the system.

        Used by the daily stale-task cleanup job, which has to run
        once for each user rather than for a single logged-in session
        — unlike Streamlit's per-session ``maybe_close_out_stale_tasks``,
        a backend-wide scheduler has no single "current user".

        Returns:
            All user_id values, in no particular order.
        """
        rows = self.fetch_all("SELECT user_id FROM users")
        return [row["user_id"] for row in rows]

    # =====================================================================
    # PLANS
    # =====================================================================

    def create_plan(
        self,
        user_id: str,
        plan_date: date,
        raw_input: str,
        ai_summary: Optional[str] = None,
        status: str = "active",
    ) -> int:
        """
        Create a new daily plan for a user.

        Args:
            user_id: Owner of the plan.
            plan_date: The calendar date this plan represents.
            raw_input: Original natural-language text from the user.
            ai_summary: Optional LLM-generated summary.
            status: Plan lifecycle state (``draft``, ``active``, ``completed``).

        Returns:
            The newly created ``plan_id``.

        Raises:
            sqlite3.IntegrityError: If a plan already exists for this user+date.
        """
        plan_id = self._insert_and_get_id(
            """
            INSERT INTO plans (user_id, plan_date, raw_input, ai_summary, status)
            VALUES (?, ?, ?, ?, ?)
            """,
            (user_id, plan_date.isoformat(), raw_input, ai_summary, status),
            pk_column="plan_id",
        )
        self.commit()
        return plan_id

    def get_today_plan(self, user_id: str) -> Optional[sqlite3.Row]:
        """
        Fetch the active plan for today, IN THE USER'S OWN TIMEZONE (not
        the server's) -- otherwise a user near a day boundary can be
        shown yesterday's/tomorrow's plan instead of their own "today".

        Args:
            user_id: Owner of the plan.

        Returns:
            Plan row, or ``None`` if no plan exists for today.
        """
        today = user_today(self, user_id).isoformat()
        return self.fetch_one(
            """
            SELECT * FROM plans
            WHERE user_id = ? AND plan_date = ?
            ORDER BY plan_id DESC
            LIMIT 1
            """,
            (user_id, today),
        )

    def get_plan_by_date(
        self,
        user_id: str,
        plan_date: date,
    ) -> Optional[sqlite3.Row]:
        """
        Fetch a user's plan for a specific date.

        Args:
            user_id: Owner of the plan.
            plan_date: Calendar date to query.

        Returns:
            Plan row, or ``None`` if not found.
        """
        return self.fetch_one(
            "SELECT * FROM plans WHERE user_id = ? AND plan_date = ?",
            (user_id, plan_date.isoformat()),
        )

    def get_plan_by_id(self, plan_id: int, user_id: str) -> Optional[sqlite3.Row]:
        """
        Fetch a plan by its primary key, scoped to its owner.

        Args:
            plan_id: Plan primary key.
            user_id: The caller's user_id. The plan is only returned if
                it actually belongs to this user -- never trust a raw
                plan_id alone, since it's just an incrementing integer
                and every other user's plan is equally guessable.

        Returns:
            Plan row, or ``None`` if not found OR not owned by user_id.
        """
        return self.fetch_one(
            "SELECT * FROM plans WHERE plan_id = ? AND user_id = ?",
            (plan_id, user_id),
        )

    def get_recent_plans(
        self,
        user_id: str,
        since_date: date,
        exclude_plan_id: Optional[int] = None,
    ) -> list[sqlite3.Row]:
        """
        Retrieve a user's plans within a rolling time window, newest first.

        Args:
            user_id: Owner of the plans.
            since_date: Only plans on or after this date are returned.
            exclude_plan_id: Optional plan id to omit (e.g. the plan
                currently being analyzed, to keep "today" out of its own
                history).

        Returns:
            List of plan rows ordered by plan_date descending.
        """
        query = "SELECT * FROM plans WHERE user_id = ? AND plan_date >= ?"
        params: list = [user_id, since_date.isoformat()]

        if exclude_plan_id is not None:
            query += " AND plan_id != ?"
            params.append(exclude_plan_id)

        query += " ORDER BY plan_date DESC, plan_id DESC"
        return self.fetch_all(query, tuple(params))

    def update_plan_status(self, plan_id: int, user_id: str, status: str) -> None:
        """
        Update the status of a plan.

        Args:
            plan_id: Target plan.
            user_id: The caller's user_id -- the update is scoped to a
                plan actually owned by this user, in the same statement,
                so there's no separate check-then-act race.
            status: New status value.

        Raises:
            OwnershipError: If plan_id doesn't exist or isn't owned by
                user_id (no row matched, so nothing was updated).
        """
        result = self.execute(
            "UPDATE plans SET status = ? WHERE plan_id = ? AND user_id = ?",
            (status, plan_id, user_id),
        )
        if result.rowcount == 0:
            self.connection.rollback()
            raise OwnershipError(
                f"Plan {plan_id} not found or not owned by user {user_id!r}."
            )
        self.commit()

    # =====================================================================
    # TASKS
    # =====================================================================

    def add_task(
        self,
        plan_id: int,
        user_id: str,
        title: str,
        category_id: Optional[int] = None,
        description: Optional[str] = None,
        priority: int = 3,
        estimated_minutes: int = 30,
        scheduled_start: Optional[str] = None,
        scheduled_end: Optional[str] = None,
        order_index: int = 0,
        is_fixed_time: bool = False,
        is_break: bool = False,
        calendar_provenance: str = "legacy_unknown",
        google_event_id: Optional[str] = None,
        google_calendar_id: Optional[str] = None,
        earliest_start: Optional[datetime] = None,
        deadline: Optional[datetime] = None,
    ) -> int:
        """Add a task and its domain event in one rollback boundary."""
        with self.transaction():
            return self._add_task_no_commit(
                plan_id=plan_id,
                user_id=user_id,
                title=title,
                category_id=category_id,
                description=description,
                priority=priority,
                estimated_minutes=estimated_minutes,
                scheduled_start=scheduled_start,
                scheduled_end=scheduled_end,
                order_index=order_index,
                is_fixed_time=is_fixed_time,
                is_break=is_break,
                calendar_provenance=calendar_provenance,
                google_event_id=google_event_id,
                google_calendar_id=google_calendar_id,
                earliest_start=earliest_start,
                deadline=deadline,
            )

    def _add_task_no_commit(
        self,
        plan_id: int,
        user_id: str,
        title: str,
        category_id: Optional[int] = None,
        description: Optional[str] = None,
        priority: int = 3,
        estimated_minutes: int = 30,
        scheduled_start: Optional[str] = None,
        scheduled_end: Optional[str] = None,
        order_index: int = 0,
        is_fixed_time: bool = False,
        is_break: bool = False,
        calendar_provenance: str = "legacy_unknown",
        google_event_id: Optional[str] = None,
        google_calendar_id: Optional[str] = None,
        earliest_start: Optional[datetime] = None,
        deadline: Optional[datetime] = None,
    ) -> int:
        """
        Add a task to an existing plan.

        Args:
            plan_id: Parent plan.
            user_id: The caller's user_id. The insert is rejected unless
                plan_id actually belongs to this user -- otherwise any
                caller could attach tasks to another user's plan just by
                guessing a plan_id.
            title: Task name (AI-extracted).
            category_id: Optional category classification.
            description: Optional extra context.
            priority: 1 (highest) to 5 (lowest).
            estimated_minutes: LLM duration estimate.
            scheduled_start: ``HH:MM`` suggested start time.
            scheduled_end: ``HH:MM`` suggested end time.
            order_index: Display order within the plan.
            is_fixed_time: True if the user/AI pinned this task to a real
                clock time (e.g. "meeting at 2pm"). Fixed-time tasks are
                never moved by the Scheduler, regardless of what work-day
                start time is chosen when re-running it. False (the
                default) means the Scheduler is free to place/move this
                task around other commitments.
            is_break: True if this row represents a user-defined break
                rather than a real task. Breaks are always fixed-time
                (the scheduler carves out exactly the window the user
                asked for) and are rendered/controlled differently in
                the UI (start/countdown instead of complete/fail).

        Returns:
            The newly created ``task_id``.

        Raises:
            OwnershipError: If plan_id doesn't exist or isn't owned by
                user_id.
        """
        owner_row = self.fetch_one(
            "SELECT plan_id,plan_date FROM plans WHERE plan_id = ? AND user_id = ?",
            (plan_id, user_id),
        )
        if owner_row is None:
            raise OwnershipError(
                f"Plan {plan_id} not found or not owned by user {user_id!r}."
            )
        if category_id is not None:
            category = self.fetch_one(
                "SELECT category_id FROM categories WHERE category_id=? AND user_id=?",
                (category_id, user_id),
            )
            if category is None:
                raise OwnershipError(
                    "Category was not found or is not owned by this user."
                )
        earliest_value = self._parse_local_planning_datetime(earliest_start)
        deadline_value = self._parse_local_planning_datetime(deadline)
        self._validate_user_local_planning_datetime(user_id, earliest_value)
        self._validate_user_local_planning_datetime(user_id, deadline_value)
        if earliest_value and deadline_value and earliest_value > deadline_value:
            raise ValueError("earliest_start must be on or before deadline.")

        task_id = self._insert_and_get_id(
            """
            INSERT INTO tasks (
                plan_id, category_id, title, description,
                priority, estimated_minutes, scheduled_start,
                scheduled_end, order_index, is_fixed_time, is_break, google_event_id,
                earliest_start, deadline
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                plan_id,
                category_id,
                title,
                description,
                priority,
                estimated_minutes,
                scheduled_start,
                scheduled_end,
                order_index,
                1 if is_fixed_time else 0,
                1 if is_break else 0,
                google_event_id,
                earliest_value.isoformat(sep=" ") if earliest_value else None,
                deadline_value.isoformat(sep=" ") if deadline_value else None,
            ),
            pk_column="task_id",
        )
        start_t = self._parse_legacy_time(scheduled_start)
        end_t = self._parse_legacy_time(scheduled_end)
        if (scheduled_start is None) != (scheduled_end is None):
            self.connection.rollback()
            raise ValueError("New scheduled tasks require both start and end times.")
        if scheduled_start is not None and (start_t is None or end_t is None or end_t <= start_t):
            self.connection.rollback()
            raise ValueError("New scheduled tasks require a valid same-day interval.")
        if start_t is not None and end_t is not None and end_t > start_t:
            if calendar_provenance not in {"imported", "coachai_created", "legacy_unknown"}:
                self.connection.rollback()
                raise ValueError(f"Invalid Calendar provenance: {calendar_provenance!r}")
            self.execute(
                """INSERT INTO task_segments
                   (task_id,segment_index,scheduled_start,scheduled_end,scheduled_date,google_event_id,
                    google_calendar_id,calendar_provenance)
                   VALUES (?,0,?,?,?,?,?,?)""",
                (
                    task_id, start_t.strftime("%H:%M:%S"), end_t.strftime("%H:%M:%S"),
                    str(owner_row["plan_date"]), google_event_id, google_calendar_id, calendar_provenance,
                ),
            )
        self.execute(
            "UPDATE plans SET schedule_revision=schedule_revision+1 WHERE plan_id=? AND user_id=?",
            (plan_id, user_id),
        )
        revision = self.fetch_one(
            "SELECT schedule_revision FROM plans WHERE plan_id=? AND user_id=?",
            (plan_id, user_id),
        )
        self._enqueue_notification_event_no_commit(
            user_id=user_id,
            event_type="TASK_CREATED",
            task_id=task_id,
            scheduled_date=str(owner_row["plan_date"]),
            schedule_revision=int(revision["schedule_revision"]),
            payload={"task_title": title, "plan_id": plan_id},
            idempotency_key=f"task-created:{task_id}",
            bump_sync_revision=True,
        )
        self.commit()
        return task_id

    @staticmethod
    def _parse_local_planning_datetime(value: Any) -> Optional[datetime]:
        if value is None or value == "":
            return None
        parsed = value if isinstance(value, datetime) else datetime.fromisoformat(str(value))
        if parsed.tzinfo is not None:
            raise ValueError("Planning constraints must be user-local wall times without a UTC offset.")
        return parsed.replace(microsecond=0)

    def _validate_user_local_planning_datetime(
        self, user_id: str, value: Optional[datetime],
    ) -> None:
        """Reject nonexistent DST wall times in the task owner's timezone."""
        if value is None:
            return
        zone = safe_zoneinfo(get_user_timezone(self, user_id))
        aware = value.replace(tzinfo=zone, fold=0)
        round_trip = aware.astimezone(timezone.utc).astimezone(zone).replace(tzinfo=None)
        if round_trip != value:
            raise ValueError(
                f"The local planning time {value.isoformat()} does not exist in timezone {zone.key}."
            )

    def _update_task_no_commit(
        self,
        task_id: int,
        user_id: str,
        **kwargs: Any,
    ) -> None:
        """
        Same as update_task(), but does NOT commit (or roll back) on its
        own -- the caller is responsible for wrapping one or more calls
        to this in a single `with self.transaction():` block and letting
        that commit everything together, or roll everything back
        together if any one of them raises. Used by
        scheduler_service._save_schedule() to make persisting an entire
        schedule atomic: either every task gets its new time slot, or
        (on any failure, including an ownership mismatch) none of them
        do, rather than leaving the schedule half-updated.

        Raises:
            OwnershipError: If task_id doesn't exist or isn't owned by
                user_id, or (when moving a task) if the destination
                plan_id isn't owned by user_id either. Raised WITHOUT
                rolling back here -- that's the enclosing transaction's
                job, so earlier calls in the same batch can be undone
                too.
        """
        allowed = {
            "title",
            "description",
            "priority",
            "estimated_minutes",
            "scheduled_start",
            "scheduled_end",
            "order_index",
            "category_id",
            "plan_id",
            "is_fixed_time",
            "is_break",
            "earliest_start",
            "deadline",
        }
        updates = {k: v for k, v in kwargs.items() if k in allowed}
        if not updates:
            return

        if "plan_id" in updates:
            dest_owner = self.fetch_one(
                "SELECT plan_id FROM plans WHERE plan_id = ? AND user_id = ?",
                (updates["plan_id"], user_id),
            )
            if dest_owner is None:
                raise OwnershipError(
                    f"Destination plan {updates['plan_id']!r} not found or "
                    f"not owned by user {user_id!r}."
                )

        if updates.get("category_id") is not None:
            category = self.fetch_one(
                "SELECT category_id FROM categories WHERE category_id=? AND user_id=?",
                (updates["category_id"], user_id),
            )
            if category is None:
                raise OwnershipError(
                    "Category was not found or is not owned by this user."
                )

        set_clause = ", ".join(f"{col} = ?" for col in updates)
        values = list(updates.values()) + [task_id, user_id]

        result = self.execute(
            f"""
            UPDATE tasks SET {set_clause}, updated_at = CURRENT_TIMESTAMP
            WHERE task_id = ?
              AND plan_id IN (SELECT plan_id FROM plans WHERE user_id = ?)
            """,
            tuple(values),
        )
        if result.rowcount == 0:
            raise OwnershipError(
                f"Task {task_id} not found or not owned by user {user_id!r}."
            )

    def update_task(
        self,
        task_id: int,
        user_id: str,
        **kwargs: Any,
    ) -> None:
        """
        Update one or more columns on a task.

        Only columns present in ``kwargs`` are modified. Safe against SQL
        injection because column names are whitelisted.

        Args:
            task_id: Target task.
            user_id: The caller's user_id. The update only takes effect
                if task_id belongs to a plan owned by this user (checked
                atomically as part of the UPDATE's WHERE clause).
            **kwargs: Column names and new values.
                      Allowed keys:
                      ``title``, ``description``, ``priority``,
                      ``estimated_minutes``, ``scheduled_start``,
                      ``scheduled_end``, ``order_index``, ``category_id``,
                      ``plan_id`` (used to move a task to a different
                      day's plan, e.g. deferring it to tomorrow -- the
                      NEW plan_id is also verified as owned by user_id,
                      so a task can never be moved into someone else's
                      plan).

        Raises:
            OwnershipError: If task_id doesn't exist or isn't owned by
                user_id, or (when moving a task) if the destination
                plan_id isn't owned by user_id either.
        """
        if "scheduled_start" in kwargs or "scheduled_end" in kwargs:
            raise ValueError(
                "scheduled_start/end are compatibility projections; use segment persistence APIs."
            )
        if "plan_id" in kwargs:
            raise ValueError("Use move_task_unscheduled() when moving a task between plans.")
        for key in ("earliest_start", "deadline"):
            if key in kwargs:
                parsed = self._parse_local_planning_datetime(kwargs[key])
                kwargs[key] = parsed.isoformat(sep=" ") if parsed else None
        if "earliest_start" in kwargs or "deadline" in kwargs:
            current = self.get_task(task_id, user_id)
            if current is None:
                raise OwnershipError(f"Task {task_id} not found or not owned by user {user_id!r}.")
            earliest = self._parse_local_planning_datetime(
                kwargs.get("earliest_start", current["earliest_start"])
            )
            deadline = self._parse_local_planning_datetime(kwargs.get("deadline", current["deadline"]))
            self._validate_user_local_planning_datetime(user_id, earliest)
            self._validate_user_local_planning_datetime(user_id, deadline)
            if earliest and deadline and earliest > deadline:
                raise ValueError("earliest_start must be on or before deadline.")
        try:
            original = self.get_task(task_id, user_id)
            if original is None:
                raise OwnershipError(
                    f"Task {task_id} not found or not owned by user {user_id!r}."
                )
            with self.transaction():
                self._update_task_no_commit(task_id, user_id, **kwargs)
                resulting_fixed = bool(kwargs.get("is_fixed_time", original["is_fixed_time"]))
                scheduling_shape_changed = (
                    "estimated_minutes" in kwargs
                    or "is_fixed_time" in kwargs
                    or "is_break" in kwargs
                )
                if scheduling_shape_changed:
                    existing_segments = self.get_task_segments(task_id, user_id)
                    self.execute("DELETE FROM task_segments WHERE task_id=?", (task_id,))
                    replacement = None
                    if resulting_fixed and len(existing_segments) == 1:
                        start = self._parse_legacy_time(existing_segments[0]["scheduled_start"])
                        duration = int(kwargs.get("estimated_minutes", original["estimated_minutes"]))
                        end_dt = datetime.combine(date.min, start) + timedelta(minutes=duration)
                        if end_dt.date() == date.min and end_dt.time() <= WORK_DAY_END:
                            replacement = (start, end_dt.time(), existing_segments[0])
                    if replacement is not None:
                        start, end, old_segment = replacement
                        self.execute(
                            """INSERT INTO task_segments
                               (task_id,segment_index,scheduled_start,scheduled_end,scheduled_date,google_event_id,
                                google_calendar_id,calendar_provenance,calendar_sync_status,calendar_sync_error)
                               VALUES (?,0,?,?,?,?,?,?,?,?)""",
                            (
                                task_id, start.strftime("%H:%M:%S"), end.strftime("%H:%M:%S"),
                                str(old_segment["effective_date"]),
                                old_segment["google_event_id"], old_segment["google_calendar_id"],
                                old_segment["calendar_provenance"],
                                "pending_update" if old_segment["calendar_provenance"] == "coachai_created" else old_segment["calendar_sync_status"],
                                old_segment["calendar_sync_error"],
                            ),
                        )
                        self._update_task_no_commit(
                            task_id, user_id,
                            scheduled_start=start.strftime("%H:%M:%S"),
                            scheduled_end=end.strftime("%H:%M:%S"),
                        )
                    else:
                        self._update_task_no_commit(
                            task_id, user_id, scheduled_start=None, scheduled_end=None,
                        )

                affected_plans = {int(original["plan_id"])}
                if "plan_id" in kwargs:
                    affected_plans.add(int(kwargs["plan_id"]))
                for plan_id in affected_plans:
                    self.execute(
                        "UPDATE plans SET schedule_revision=schedule_revision+1 WHERE plan_id=? AND user_id=?",
                        (plan_id, user_id),
                    )
                plan_after = self.get_plan_by_id(int(original["plan_id"]), user_id)
                self._enqueue_notification_event_no_commit(
                    user_id=user_id,
                    event_type="TASK_UPDATED",
                    task_id=task_id,
                    scheduled_date=str(plan_after["plan_date"]),
                    schedule_revision=int(plan_after["schedule_revision"] or 0),
                    payload={
                        "task_title": str(kwargs.get("title", original["title"])),
                        "plan_id": int(original["plan_id"]),
                        "changed_fields": sorted(kwargs),
                    },
                    idempotency_key=(
                        f"task-updated:{task_id}:{plan_after['schedule_revision']}:"
                        f"{','.join(sorted(kwargs))}"
                    ),
                    bump_sync_revision=True,
                )
        except OwnershipError:
            self.connection.rollback()
            raise

    def delete_task(self, task_id: int, user_id: str) -> None:
        """
        Remove a task permanently.

        Args:
            task_id: Task to delete.
            user_id: The caller's user_id. The delete only takes effect
                if task_id belongs to a plan owned by this user.

        Raises:
            OwnershipError: If task_id doesn't exist or isn't owned by
                user_id.
        """
        with self.transaction():
            task = self.get_task(task_id, user_id)
            if task is None:
                raise OwnershipError(
                    f"Task {task_id} not found or not owned by user {user_id!r}."
                )
            plan = self.get_plan_by_id(int(task["plan_id"]), user_id)
            result = self.execute(
                """
                DELETE FROM tasks
                WHERE task_id = ?
                  AND plan_id IN (SELECT plan_id FROM plans WHERE user_id = ?)
                """,
                (task_id, user_id),
            )
            if result.rowcount == 0:
                raise OwnershipError(
                    f"Task {task_id} not found or not owned by user {user_id!r}."
                )
            self.execute(
                "UPDATE plans SET schedule_revision=schedule_revision+1 WHERE plan_id=? AND user_id=?",
                (task["plan_id"], user_id),
            )
            revision = int(plan["schedule_revision"] or 0) + 1
            self._enqueue_notification_event_no_commit(
                user_id=user_id,
                event_type="TASK_DELETED",
                task_id=task_id,
                scheduled_date=str(plan["plan_date"]),
                schedule_revision=revision,
                payload={"task_title": str(task["title"]), "plan_id": int(task["plan_id"])},
                idempotency_key=f"task-deleted:{task_id}:{revision}",
                bump_sync_revision=True,
            )

    def get_tasks_by_plan(self, plan_id: int, user_id: str) -> list[sqlite3.Row]:
        """
        Retrieve all tasks belonging to a plan, ordered by display index.

        Args:
            plan_id: Parent plan.
            user_id: The caller's user_id. Returns an empty list (same
                as "plan doesn't exist") if plan_id isn't owned by this
                user, rather than leaking another user's tasks.

        Returns:
            List of task rows.
        """
        return self.fetch_all(
            """
            SELECT t.*, c.name AS category_name FROM tasks t
            JOIN plans p ON t.plan_id = p.plan_id
            LEFT JOIN categories c ON t.category_id = c.category_id
            WHERE t.plan_id = ? AND p.user_id = ?
            ORDER BY t.order_index ASC, t.task_id ASC
            """,
            (plan_id, user_id),
        )

    def get_tasks_by_plan_ids(
        self, plan_ids: list[int], user_id: str,
    ) -> list[sqlite3.Row]:
        """
        Batch counterpart to get_tasks_by_plan(): fetch every task for
        ALL of the given plans in a single round trip, instead of one
        query per plan (the N+1 pattern services.load_recent_plans()
        used to have -- 1 query for the plans, then 1 more per plan for
        that plan's tasks).

        Args:
            plan_ids: The plans to fetch tasks for. An empty list short-
                circuits to an empty result with no query at all.
            user_id: The caller's user_id. Only tasks belonging to a
                plan owned by this user are returned (same tenant-
                isolation guarantee as get_tasks_by_plan), so a
                plan_id belonging to another user silently yields no
                rows for that id rather than leaking its tasks.

        Returns:
            Every matching task row, ordered by plan_id (ascending, to
            make grouping by plan trivial for the caller) then by
            display index within each plan (order_index ASC, task_id
            ASC -- the same ordering get_tasks_by_plan uses). Callers
            should group these by row["plan_id"] themselves; this
            deliberately doesn't return a dict-of-lists so it stays a
            plain, cacheable query result like every other fetch_all
            call in this class.
        """
        if not plan_ids:
            return []
        # Plain "?" placeholders, one per id -- consistent with every
        # other query in this class; _to_named() below turns these into
        # SQLAlchemy's named-param style. SQLite's default bound-
        # parameter ceiling (~999) is far above any realistic number of
        # plans in one history window, so no batching/chunking here.
        placeholders = ",".join(["?"] * len(plan_ids))
        query = f"""
            SELECT t.* FROM tasks t
            JOIN plans p ON t.plan_id = p.plan_id
            WHERE t.plan_id IN ({placeholders}) AND p.user_id = ?
            ORDER BY t.plan_id ASC, t.order_index ASC, t.task_id ASC
        """
        params = tuple(plan_ids) + (user_id,)
        return self.fetch_all(query, params)

    def get_task(self, task_id: int, user_id: str) -> Optional[sqlite3.Row]:
        """
        Fetch a single task by ID, scoped to its owner.

        Args:
            task_id: Task primary key.
            user_id: The caller's user_id. The task is only returned if
                it belongs to a plan owned by this user.

        Returns:
            Task row, or ``None`` if not found OR not owned by user_id.
        """
        return self.fetch_one(
            """
            SELECT t.* FROM tasks t
            JOIN plans p ON t.plan_id = p.plan_id
            WHERE t.task_id = ? AND p.user_id = ?
            """,
            (task_id, user_id),
        )

    def set_task_timer_state(
        self,
        task_id: int,
        user_id: str,
        accumulated_seconds: int,
        segment_started_at: Optional[str],
        paused_at: Optional[str],
        total_paused_seconds: int,
        expected_version: Optional[int] = None,
    ) -> int:
        """
        Directly set a task's full pause/resume timer state. Low-level —
        callers (services.start_task_timer/pause_task_timer/
        resume_task_timer/finish_task_with_timer) are responsible for
        computing the right values; this just persists them all in one
        statement (they always change together, so there's no reason to
        risk a partial update between two separate calls).

        Optimistic concurrency: every successful write bumps
        timer_version by 1. If ``expected_version`` is given, the write
        only applies when the row's CURRENT timer_version still matches
        it -- i.e. nothing else updated this task's timer in between
        the caller's read and this write. This closes a lost-update
        race: start/pause/resume/finish all read the current state,
        compute a new state from it, then call this method to persist
        it, so two overlapping calls (a double-submitted click, two
        open tabs, a retried request) can otherwise both read the same
        starting point and the second blind write silently clobbers the
        first, dropping whatever time the first call banked.
        ``expected_version=None`` (the default) skips the check
        entirely, for any caller that intentionally wants a last-write-
        wins overwrite.

        Args:
            task_id: Target task.
            user_id: The caller's user_id. The update only takes effect
                if task_id belongs to a plan owned by this user.
            accumulated_seconds: Total active seconds banked from every
                completed run segment (excludes the current one).
            segment_started_at: ``YYYY-MM-DD HH:MM:SS`` UTC timestamp
                marking the start of the currently-running active
                segment, or None if paused/not started/finished.
            paused_at: ``YYYY-MM-DD HH:MM:SS`` UTC timestamp marking the
                start of the CURRENT pause, or None if not paused.
            total_paused_seconds: Cumulative duration of every FINISHED
                pause (the currently-open one, if any, is on top of
                this at read time, not included here yet).
            expected_version: The timer_version the caller last read.
                When given, the update is conditional on the row still
                being at this version.

        Returns:
            The row's new timer_version after this write.

        Raises:
            OwnershipError: If task_id doesn't exist or isn't owned by
                user_id.
            TimerConflictError: If expected_version was given and no
                longer matches the row's current timer_version (task
                exists and is owned by user_id, but was updated by
                someone/something else first).
        """
        base_sql = """
            UPDATE tasks
            SET timer_accumulated_seconds = ?,
                timer_segment_started_at = ?,
                paused_at = ?,
                timer_total_paused_seconds = ?,
                timer_version = timer_version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE task_id = ?
              AND plan_id IN (SELECT plan_id FROM plans WHERE user_id = ?)
        """
        params: list[Any] = [
            accumulated_seconds, segment_started_at, paused_at,
            total_paused_seconds, task_id, user_id,
        ]
        if expected_version is not None:
            base_sql += " AND timer_version = ?"
            params.append(expected_version)

        result = self.execute(base_sql, tuple(params))
        if result.rowcount == 0:
            self.connection.rollback()
            current = self.get_task(task_id, user_id)
            if current is None:
                raise OwnershipError(
                    f"Task {task_id} not found or not owned by user {user_id!r}."
                )
            raise TimerConflictError(
                f"Task {task_id}'s timer state changed concurrently "
                f"(expected version {expected_version}, now "
                f"{current.get('timer_version')})."
            )
        self.commit()
        updated = self.get_task(task_id, user_id)
        return int(updated["timer_version"]) if updated else (
            (expected_version + 1) if expected_version is not None else 0
        )

    def increment_task_pause_count(self, task_id: int, user_id: str) -> None:
        """Bump a task's pause_count by 1 (called once per Pause click).

        Raises:
            OwnershipError: If task_id doesn't exist or isn't owned by
                user_id.
        """
        result = self.execute(
            """
            UPDATE tasks SET pause_count = pause_count + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE task_id = ?
              AND plan_id IN (SELECT plan_id FROM plans WHERE user_id = ?)
            """,
            (task_id, user_id),
        )
        if result.rowcount == 0:
            self.connection.rollback()
            raise OwnershipError(
                f"Task {task_id} not found or not owned by user {user_id!r}."
            )
        self.commit()

    def get_pause_matrix(
        self,
        user_id: str,
        since_date: str,
    ) -> list[dict]:
        """
        Build the Category x Time-of-day focus matrix: for every task
        this user started within the window, bucket it by the category
        it belongs to and the hour-of-day it was FIRST started
        (started_at), and sum pause_count within each bucket.

        This is the raw data behind "when/on what do you lose focus
        most" — e.g. a user who consistently pauses 'Study' tasks
        started in the Evening bucket, but rarely pauses 'Study' tasks
        started in the Morning, has a clear, actionable pattern.
        Deliberately returned as flat (category, time_bucket) rows
        rather than a pre-pivoted grid, so any caller (a UI table, the
        AI Coach's recommendation prompt, a future analytics chart) can
        reshape it however it needs without re-querying.

        Only tasks with started_at set are included (a task that was
        never started has no time-of-day to bucket it by). Uncategorised
        tasks are grouped under 'Uncategorised'.

        Args:
            user_id: Whose tasks to analyze.
            since_date: ISO date string — only plans on/after this date.

        Returns:
            List of dicts: {category, time_bucket, task_count,
            paused_task_count, total_pauses, avg_pauses_per_task}.
            time_bucket is one of 'Morning' (5-12), 'Afternoon' (12-17),
            'Evening' (17-21), 'Night' (21-5).
        """
        rows = self.fetch_all(
            """
            SELECT
                COALESCE(c.name, 'Uncategorised') AS category,
                t.started_at AS started_at,
                t.pause_count AS pause_count,
                t.timer_total_paused_seconds AS timer_total_paused_seconds
            FROM tasks t
            JOIN plans p ON t.plan_id = p.plan_id
            LEFT JOIN categories c ON t.category_id = c.category_id
            WHERE p.user_id = ?
              AND p.plan_date >= ?
              AND t.started_at IS NOT NULL
              AND t.is_break = 0
            """,
            (user_id, since_date),
        )

        tz_name = get_user_timezone(self, user_id)

        def _bucket(started_at_raw) -> str:
            # started_at is stored as a naive UTC timestamp -- bucket by
            # the HOUR IN THE USER'S OWN TIMEZONE, not whatever hour the
            # raw UTC string happens to show. Taking .hour directly off
            # the UTC string here used to put a user's evening tasks
            # into the wrong bucket for anyone not physically in UTC.
            hour = local_hour_from_utc_string(started_at_raw, tz_name)
            if hour is None:
                return "Unknown"
            if 5 <= hour < 12:
                return "Morning"
            if 12 <= hour < 17:
                return "Afternoon"
            if 17 <= hour < 21:
                return "Evening"
            return "Night"

        cells: dict[tuple[str, str], dict[str, int]] = {}
        for row in rows:
            key = (row["category"], _bucket(row["started_at"]))
            cell = cells.setdefault(
                key, {
                    "task_count": 0, "paused_task_count": 0, "total_pauses": 0,
                    "total_paused_seconds": 0,
                }
            )
            cell["task_count"] += 1
            pause_count = int(row["pause_count"] or 0)
            cell["total_pauses"] += pause_count
            cell["total_paused_seconds"] += int(row["timer_total_paused_seconds"] or 0)
            if pause_count > 0:
                cell["paused_task_count"] += 1

        matrix: list[dict] = []
        for (category, time_bucket), cell in cells.items():
            matrix.append({
                "category": category,
                "time_bucket": time_bucket,
                "task_count": cell["task_count"],
                "paused_task_count": cell["paused_task_count"],
                "total_pauses": cell["total_pauses"],
                "avg_pauses_per_task": (
                    cell["total_pauses"] / cell["task_count"] if cell["task_count"] else 0.0
                ),
                "total_paused_seconds": cell["total_paused_seconds"],
                "avg_pause_duration_seconds": (
                    cell["total_paused_seconds"] / cell["total_pauses"]
                    if cell["total_pauses"] else 0.0
                ),
            })
        matrix.sort(key=lambda r: (-r["total_pauses"], r["category"], r["time_bucket"]))
        return matrix

    def update_task_status(
        self,
        task_id: int,
        user_id: str,
        status: str,
        failure_reason: Optional[str] = None,
        actual_minutes: Optional[int] = None,
    ) -> None:
        """Update lifecycle state and enqueue its event atomically."""
        with self.transaction():
            self._update_task_status_no_commit(
                task_id, user_id, status,
                failure_reason=failure_reason,
                actual_minutes=actual_minutes,
            )

    def _update_task_status_no_commit(
        self,
        task_id: int,
        user_id: str,
        status: str,
        failure_reason: Optional[str] = None,
        actual_minutes: Optional[int] = None,
    ) -> None:
        """
        Update a task's status.

        Timing behaviour:
            - status == 'in_progress': stamps ``started_at`` with now,
              starting the timer (only if not already started, so
              re-clicking Start doesn't reset an in-flight timer).
            - status in ('completed', 'failed'): stamps ``completed_at``
              with now. If ``actual_minutes`` isn't explicitly passed in,
              it is auto-computed as the elapsed minutes since
              ``started_at``. If the task was never started (went
              straight from pending to completed/failed), it falls back
              to the task's own ``estimated_minutes``.

        Args:
            task_id: Target task.
            user_id: The caller's user_id. The update only takes effect
                if task_id belongs to a plan owned by this user.
            status: New status ('in_progress', 'completed', or 'failed').
            failure_reason: Required when status is 'failed'.
            actual_minutes: Optional explicit override for actual
                duration. Leave unset to let the timer compute it.

        Raises:
            OwnershipError: If task_id doesn't exist or isn't owned by
                user_id.
        """
        before_task = self.get_task(task_id, user_id)
        if before_task is None:
            raise OwnershipError(
                f"Task {task_id} not found or not owned by user {user_id!r}."
            )
        previous_status = str(before_task["status"])
        now_utc = datetime.now(timezone.utc).replace(tzinfo=None)
        now_utc_str = now_utc.strftime("%Y-%m-%d %H:%M:%S")

        if status == "in_progress":
            result = self.execute(
                """
                UPDATE tasks
                SET status = ?,
                    started_at = COALESCE(started_at, ?),
                    updated_at = ?
                WHERE task_id = ?
                  AND plan_id IN (SELECT plan_id FROM plans WHERE user_id = ?)
                """,
                (status, now_utc_str, now_utc_str, task_id, user_id),
            )
            if result.rowcount == 0:
                raise OwnershipError(
                    f"Task {task_id} not found or not owned by user {user_id!r}."
                )
            if previous_status != status:
                self._enqueue_notification_event_no_commit(
                    user_id=user_id,
                    event_type="TASK_STARTED",
                    task_id=task_id,
                    payload={"task_title": str(before_task["title"]), "previous_status": previous_status},
                    idempotency_key=f"task-status:{task_id}:{status}:{now_utc.isoformat()}",
                    bump_sync_revision=True,
                )
            self.commit()
            return

        completed_at = now_utc_str if status in ("completed", "failed") else None

        if status in ("completed", "failed") and actual_minutes is None:
            task = self.get_task(task_id, user_id)
            started_at_raw = task["started_at"] if task is not None else None
            if started_at_raw:
                try:
                    started_dt = datetime.fromisoformat(str(started_at_raw))
                    elapsed = (now_utc - started_dt).total_seconds() / 60.0
                    actual_minutes = max(1, round(elapsed))
                except (ValueError, TypeError):
                    actual_minutes = task["estimated_minutes"] if task is not None else None
            elif task is not None:
                # Never started (went straight to completed/failed) — no
                # timer data exists, so fall back to the estimate.
                actual_minutes = task["estimated_minutes"]

        result = self.execute(
            """
            UPDATE tasks
            SET status = ?,
                failure_reason = ?,
                actual_minutes = ?,
                completed_at = ?,
                updated_at = ?
            WHERE task_id = ?
              AND plan_id IN (SELECT plan_id FROM plans WHERE user_id = ?)
            """,
            (status, failure_reason, actual_minutes, completed_at, now_utc_str, task_id, user_id),
        )
        if result.rowcount == 0:
            raise OwnershipError(
                f"Task {task_id} not found or not owned by user {user_id!r}."
            )
        event_type = {"completed": "TASK_COMPLETED", "failed": "TASK_FAILED"}.get(status)
        if event_type and previous_status != status:
            self._enqueue_notification_event_no_commit(
                user_id=user_id,
                event_type=event_type,
                task_id=task_id,
                payload={
                    "task_title": str(before_task["title"]),
                    "previous_status": previous_status,
                    "actual_minutes": actual_minutes,
                    "failure_reason": failure_reason,
                },
                idempotency_key=f"task-status:{task_id}:{status}:{now_utc.isoformat()}",
                bump_sync_revision=True,
            )
        self.commit()

    @staticmethod
    def _stale_task_actual_minutes(
        task: Optional[dict], plan_date_str: str, tz_name: str = "UTC"
    ) -> Optional[int]:
        """
        Work out actual_minutes for an in_progress task being auto-closed
        by close_out_stale_tasks, without ever crediting time past the
        end of the task's own plan day (23:59:59, IN THE USER'S OWN
        TIMEZONE) or time spent paused.

        - If the timer's active segment is still running
          (timer_segment_started_at set), add only up to end-of-day for
          that segment on top of whatever's already banked in
          timer_accumulated_seconds.
        - If the timer is paused (or was never started via the
          pause/resume UI at all — e.g. a "break"), use whatever's
          already banked, falling back to a plain started_at diff
          (also capped) when there's no timer data at all.

        Args:
            task: The stale task row.
            plan_date_str: The task's plan's ``plan_date`` (ISO date),
                interpreted as a LOCAL calendar date in ``tz_name``.
            tz_name: The plan owner's IANA timezone. All timer/started_at
                values are naive UTC in storage; this is what lets us
                compute the correct UTC instant for "end of THIS user's
                day" instead of assuming the server's local timezone.

        Returns None when there's nothing to compute from, so the
        caller falls back to update_task_status's own estimated_minutes
        fallback.
        """
        if task is None:
            return None

        plan_local_date = datetime.strptime(plan_date_str, "%Y-%m-%d").date()
        tz = safe_zoneinfo(tz_name)
        # The UTC instant corresponding to 23:59:59 local time on the
        # plan's own date -- NOT a naive 23:59:59, which silently
        # assumes the server's local timezone equals the user's.
        end_of_day_local = datetime.combine(
            plan_local_date, datetime.max.time().replace(microsecond=0)
        ).replace(tzinfo=tz)
        end_of_day = end_of_day_local.astimezone(timezone.utc).replace(tzinfo=None)
        now_utc = datetime.now(timezone.utc).replace(tzinfo=None)
        cap = min(now_utc, end_of_day)

        accumulated_seconds = int(task.get("timer_accumulated_seconds") or 0)
        segment_started_raw = task.get("timer_segment_started_at")

        if segment_started_raw:
            try:
                segment_started = datetime.fromisoformat(str(segment_started_raw))
                extra_seconds = max(0, (cap - segment_started).total_seconds())
                return max(1, round((accumulated_seconds + extra_seconds) / 60))
            except (ValueError, TypeError):
                pass

        if accumulated_seconds > 0:
            return max(1, round(accumulated_seconds / 60))

        # No timer data at all — e.g. a "break", or in_progress set some
        # other way. Fall back to started_at, still capped at end-of-day.
        started_at_raw = task.get("started_at")
        if started_at_raw:
            try:
                started_dt = datetime.fromisoformat(str(started_at_raw))
                elapsed_seconds = max(0, (cap - started_dt).total_seconds())
                return max(1, round(elapsed_seconds / 60))
            except (ValueError, TypeError):
                pass

        return None

    def close_out_stale_tasks(self, user_id: str) -> int:
        """CAS-close past-day tasks atomically and emit durable effects.

        This maintenance transition is intentionally separate from the
        user-command state machine: it is a server-owned end-of-day failure.
        A concurrent user command wins through the version/status predicate.
        """

        tz_name = get_user_timezone(self, user_id)
        today = user_today(self, user_id).isoformat()
        stale = self.fetch_all(
            """SELECT t.task_id,t.status,t.version,t.title,t.estimated_minutes,
                      p.plan_date AS plan_date
                 FROM tasks t JOIN plans p ON p.plan_id=t.plan_id
                WHERE p.user_id=? AND p.plan_date<?
                  AND t.status IN ('pending','in_progress')""",
            (user_id, today),
        )
        now_utc = datetime.now(timezone.utc).replace(tzinfo=None)
        now_text = now_utc.strftime("%Y-%m-%d %H:%M:%S")
        closed = 0
        with self.transaction():
            for row in stale:
                task = self.get_task(int(row["task_id"]), user_id)
                if task is None:
                    continue
                actual_minutes = 0
                if row["status"] == "in_progress":
                    calculated = self._stale_task_actual_minutes(
                        task, str(row["plan_date"]), tz_name
                    )
                    actual_minutes = int(
                        calculated
                        if calculated is not None
                        else task.get("estimated_minutes") or 0
                    )
                result = self.execute(
                    """UPDATE tasks
                          SET status='failed',failure_reason='Ran out of time',
                              actual_minutes=?,completed_at=?,updated_at=?,
                              timer_accumulated_seconds=?,
                              timer_segment_started_at=NULL,paused_at=NULL,
                              timer_version=timer_version+1,version=version+1
                        WHERE task_id=? AND version=? AND status=?
                          AND plan_id IN
                              (SELECT plan_id FROM plans WHERE user_id=?)""",
                    (
                        actual_minutes,
                        now_text,
                        now_text,
                        max(0, actual_minutes * 60),
                        int(row["task_id"]),
                        int(row.get("version") or 0),
                        str(row["status"]),
                        user_id,
                    ),
                )
                if result.rowcount != 1:
                    continue
                self._enqueue_notification_event_no_commit(
                    user_id=user_id,
                    event_type="TASK_FAILED",
                    task_id=int(row["task_id"]),
                    scheduled_date=str(row["plan_date"]),
                    payload={
                        "task_title": str(row["title"]),
                        "previous_status": str(row["status"]),
                        "actual_minutes": actual_minutes,
                        "failure_reason": "Ran out of time",
                    },
                    idempotency_key=(
                        f"stale-close:{int(row['task_id'])}:"
                        f"{int(row.get('version') or 0) + 1}"
                    ),
                    bump_sync_revision=True,
                )
                closed += 1
        return closed

    def _legacy_close_out_stale_tasks(self, user_id: str) -> int:
        """
        Auto-close any task left ``pending``/``in_progress`` on a
        past-dated plan for this user, so tasks never sit unresolved
        indefinitely and always get counted in analytics.

        - A task that was never started (``pending``) is marked
          ``failed`` with ``actual_minutes = 0`` — there's no real
          timer data to report since it was never touched.
        - A task that was started but never finished (``in_progress``)
          is marked ``failed`` with ``actual_minutes`` computed by
          ``_stale_task_actual_minutes``: pause-aware, and capped at
          23:59:59 of the task's *own* plan day in THIS USER'S timezone
          — never the server's local timezone, and never at "whenever
          this cleanup happened to run".

        Both get ``failure_reason = 'Ran out of time'`` — a signal the
        AI Coach can use to notice overcommitment patterns (e.g. "you
        keep planning more than you can realistically finish").

        Args:
            user_id: The plans' owner.

        Returns:
            The number of tasks that were closed out.
        """
        tz_name = get_user_timezone(self, user_id)
        today = user_today(self, user_id).isoformat()
        stale = self.fetch_all(
            """
            SELECT tasks.task_id, tasks.status, plans.plan_date AS plan_date
            FROM tasks
            JOIN plans ON plans.plan_id = tasks.plan_id
            WHERE plans.user_id = ?
              AND plans.plan_date < ?
              AND tasks.status IN ('pending', 'in_progress')
            """,
            (user_id, today),
        )

        now_utc = datetime.now(timezone.utc).replace(tzinfo=None)
        now_utc_str = now_utc.strftime("%Y-%m-%d %H:%M:%S")

        for row in stale:
            if row["status"] == "pending":
                self.execute(
                    """
                    UPDATE tasks
                    SET status = 'failed',
                        failure_reason = 'Ran out of time',
                        actual_minutes = 0,
                        completed_at = ?,
                        updated_at = ?
                    WHERE task_id = ?
                    """,
                    (now_utc_str, now_utc_str, row["task_id"]),
                )
            else:  # in_progress — compute real elapsed time ourselves,
                # respecting any paused stretches and capping at the end
                # of the task's own plan day (in the user's timezone,
                # not "whenever this cleanup happens to run", which
                # could be many hours later).
                task = self.get_task(row["task_id"], user_id)
                capped_minutes = self._stale_task_actual_minutes(
                    task, str(row["plan_date"]), tz_name
                )
                self.update_task_status(
                    row["task_id"], user_id, status="failed", failure_reason="Ran out of time",
                    actual_minutes=capped_minutes,
                )

        self.commit()
        return len(stale)

    def get_recent_tasks_for_user(
        self,
        user_id: str,
        since_date: date,
        exclude_plan_id: Optional[int] = None,
    ) -> list[sqlite3.Row]:
        """
        Retrieve a user's tasks within a rolling time window, newest first.

        Joins to plans (to scope by user_id, expose plan_date, and apply
        the time window) and to categories (to expose a readable category
        name). Used by the Recommendation layer to build historical
        context — Recommendation only reads this data, it never computes
        statistics from it.

        Args:
            user_id: Owner of the tasks, via their plans.
            since_date: Only tasks belonging to plans on or after this
                date are returned.
            exclude_plan_id: Optional plan id to omit.

        Returns:
            List of task rows (plus plan_date and category_name) ordered
            by plan_date descending, then order_index ascending.
        """
        query = """
            SELECT
                t.*,
                p.plan_date AS plan_date,
                c.name AS category_name
            FROM tasks t
            JOIN plans p ON t.plan_id = p.plan_id
            LEFT JOIN categories c ON t.category_id = c.category_id
            WHERE p.user_id = ? AND p.plan_date >= ?
        """
        params: list = [user_id, since_date.isoformat()]

        if exclude_plan_id is not None:
            query += " AND p.plan_id != ?"
            params.append(exclude_plan_id)

        query += " ORDER BY p.plan_date DESC, t.order_index ASC"
        return self.fetch_all(query, tuple(params))

    def get_failure_reason_counts(
        self,
        user_id: str,
        since_date: date,
        exclude_plan_id: Optional[int] = None,
    ) -> list[sqlite3.Row]:
        """
        Compute how many times each failure_reason occurred for a user's
        failed tasks within a rolling time window.

        This is an aggregate statistic derived directly from stored
        data — the same category of work as main_failure_reason already
        cached in user_profiles. It belongs at the data layer:
        Recommendation consumes this distribution, it does not compute
        it.

        Args:
            user_id: Owner of the tasks, via their plans.
            since_date: Only tasks belonging to plans on or after this
                date are counted.
            exclude_plan_id: Optional plan id to omit.

        Returns:
            Rows of (failure_reason, occurrence_count), ordered by count
            descending. Tasks with no recorded failure_reason are
            excluded.
        """
        query = """
            SELECT
                t.failure_reason AS failure_reason,
                COUNT(*) AS occurrence_count
            FROM tasks t
            JOIN plans p ON t.plan_id = p.plan_id
            WHERE p.user_id = ?
              AND p.plan_date >= ?
              AND t.failure_reason IS NOT NULL
        """
        params: list = [user_id, since_date.isoformat()]

        if exclude_plan_id is not None:
            query += " AND p.plan_id != ?"
            params.append(exclude_plan_id)

        query += " GROUP BY t.failure_reason ORDER BY occurrence_count DESC"
        return self.fetch_all(query, tuple(params))

    # =====================================================================
    # USER PROFILES
    # =====================================================================

    def get_profile(self, user_id: str) -> Optional[sqlite3.Row]:
        """
        Retrieve the pre-computed analytics profile for a user.

        Args:
            user_id: Target user.

        Returns:
            Profile row, or ``None`` if the user does not exist.
        """
        return self.fetch_one(
            "SELECT * FROM user_profiles WHERE user_id = ?",
            (user_id,),
        )

    def update_profile(
        self,
        user_id: str,
        **kwargs: Any,
    ) -> None:
        """
        Update analytics fields in the user's profile.

        Called by the Python behaviour-analysis script after processing
        historical task data.

        Args:
            user_id: Target user.
            **kwargs: Allowed keys —
                      ``completion_rate``, ``productivity_score``,
                      ``best_productivity_hour``, ``avg_delay_minutes``,
                      ``main_failure_reason``, ``favorite_category_id``,
                      ``current_streak``, ``longest_streak``,
                      ``total_completed``, ``total_failed``, ``total_tasks``.
        """
        allowed = {
            "completion_rate",
            "productivity_score",
            "best_productivity_hour",
            "avg_delay_minutes",
            "main_failure_reason",
            "favorite_category_id",
            "current_streak",
            "longest_streak",
            "total_completed",
            "total_failed",
            "total_tasks",
        }
        updates = {k: v for k, v in kwargs.items() if k in allowed}
        if not updates:
            return

        if updates.get("favorite_category_id") is not None:
            category = self.fetch_one(
                "SELECT category_id FROM categories WHERE category_id=? AND user_id=?",
                (updates["favorite_category_id"], user_id),
            )
            if category is None:
                raise OwnershipError(
                    "Favorite category was not found or is not owned by this user."
                )

        set_clause = ", ".join(f"{col} = ?" for col in updates)
        values = list(updates.values()) + [user_id]

        self.execute(
            f"""
            UPDATE user_profiles
            SET {set_clause}, last_updated = CURRENT_TIMESTAMP
            WHERE user_id = ?
            """,
            tuple(values),
        )
        self.commit()

    # =====================================================================
    # BADGES
    # =====================================================================

    def award_badge(self, user_id: str, badge_id: int) -> Optional[int]:
        """
        Award a badge to a user if they do not already have it.

        Args:
            user_id: Recipient.
            badge_id: Badge to award.

        Returns:
            The ``user_badge_id`` if newly awarded, or ``None`` if the user
            already possesses this badge.

        Raises:
            sqlite3.IntegrityError: If the badge_id does not exist.
        """
        try:
            new_id = self._insert_and_get_id(
                """
                INSERT INTO user_badges (user_id, badge_id)
                VALUES (?, ?)
                """,
                (user_id, badge_id),
                pk_column="user_badge_id",
            )
            self.commit()
            return new_id
        except IntegrityError:
            # UNIQUE(user_id, badge_id) violation – already earned
            self.connection.rollback()
            return None

    def get_user_badges(self, user_id: str) -> list[sqlite3.Row]:
        """
        Retrieve all badges earned by a user.

        Args:
            user_id: Target user.

        Returns:
            List of joined user_badge + badge rows.
        """
        return self.fetch_all(
            """
            SELECT
                ub.user_badge_id,
                ub.earned_at,
                b.badge_id,
                b.name,
                b.description,
                b.icon,
                b.requirement_type,
                b.requirement_value
            FROM user_badges ub
            JOIN badges b ON ub.badge_id = b.badge_id
            WHERE ub.user_id = ?
            ORDER BY ub.earned_at DESC
            """,
            (user_id,),
        )

    def get_all_badges(self) -> list[sqlite3.Row]:
        """
        Retrieve all badge definitions.

        Returns:
            List of badge rows.
        """
        return self.fetch_all("SELECT * FROM badges ORDER BY badge_id")

    def create_badge(
        self,
        name: str,
        description: str,
        requirement_type: str,
        requirement_value: int,
        icon: Optional[str] = None,
    ) -> int:
        """
        Insert a new badge definition (typically used during seeding).

        Args:
            name: Unique badge name.
            description: How the badge is earned.
            requirement_type: ``streak``, ``count``, or ``rate``.
            requirement_value: Numeric threshold.
            icon: Optional icon path or URL.

        Returns:
            The newly created ``badge_id``.
        """
        badge_id = self._insert_and_get_id(
            """
            INSERT INTO badges (name, description, icon, requirement_type, requirement_value)
            VALUES (?, ?, ?, ?, ?)
            """,
            (name, description, icon, requirement_type, requirement_value),
            pk_column="badge_id",
        )
        self.commit()
        return badge_id

    # =====================================================================
    # CATEGORIES (bonus helpers)
    # =====================================================================

    def create_category(
        self,
        user_id: str,
        name: str,
        color: str = "#3B82F6",
    ) -> int:
        """
        Create a custom category for a user.

        Args:
            user_id: Owner.
            name: Category name (unique per user).
            color: Hex colour code.

        Returns:
            The newly created ``category_id``.

        Raises:
            sqlite3.IntegrityError: If the user already has this category name.
        """
        category_id = self._insert_and_get_id(
            "INSERT INTO categories (user_id, name, color) VALUES (?, ?, ?)",
            (user_id, name, color),
            pk_column="category_id",
        )
        self.commit()
        return category_id

    def get_categories(self, user_id: str) -> list[sqlite3.Row]:
        """
        List all categories belonging to a user.

        Args:
            user_id: Target user.

        Returns:
            List of category rows.
        """
        return self.fetch_all(
            "SELECT * FROM categories WHERE user_id = ? ORDER BY name",
            (user_id,),
        )

    # ------------------------------------------------------------------
    # Google OAuth Tokens
    # ------------------------------------------------------------------

    def save_google_tokens(
        self,
        user_id: str,
        access_token: str,
        refresh_token: str,
        token_expiry: str,
        scopes: str = "",
    ) -> int:
        """Insert or replace Google OAuth tokens for a user."""
        token_id = self._insert_and_get_id(
            """
            INSERT INTO google_oauth_tokens
                (user_id, access_token, refresh_token, token_expiry, scopes)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET
                access_token = excluded.access_token,
                refresh_token = excluded.refresh_token,
                token_expiry = excluded.token_expiry,
                scopes = excluded.scopes,
                updated_at = CURRENT_TIMESTAMP
            """,
            (user_id, access_token, refresh_token, token_expiry, scopes),
            pk_column="token_id",
        )
        self.commit()
        return token_id

    def get_google_tokens(self, user_id: str) -> Optional[sqlite3.Row]:
        """Fetch stored Google OAuth tokens for a user."""
        return self.fetch_one(
            "SELECT * FROM google_oauth_tokens WHERE user_id = ?",
            (user_id,),
        )

    def update_google_tokens(
        self,
        user_id: str,
        access_token: str,
        token_expiry: str,
    ) -> None:
        """Update access token and expiry after a refresh."""
        self.execute(
            """
            UPDATE google_oauth_tokens
            SET access_token = ?, token_expiry = ?, updated_at = CURRENT_TIMESTAMP
            WHERE user_id = ?
            """,
            (access_token, token_expiry, user_id),
        )
        self.commit()

    def delete_google_tokens(self, user_id: str) -> None:
        """Remove Google OAuth tokens (disconnect)."""
        self.execute(
            "DELETE FROM google_oauth_tokens WHERE user_id = ?",
            (user_id,),
        )
        self.commit()

    # ------------------------------------------------------------------
    # Google Selected Calendars
    # ------------------------------------------------------------------

    def save_selected_calendars(
        self,
        user_id: str,
        calendars: list[dict],
    ) -> None:
        """
        Replace all selected calendars for a user.

        Args:
            calendars: List of dicts with keys:
                calendar_id, calendar_name, color, is_primary
        """
        with self.transaction():
            self.execute(
                "DELETE FROM google_selected_calendars WHERE user_id = ?",
                (user_id,),
            )
            for cal in calendars:
                self.execute(
                    """
                    INSERT INTO google_selected_calendars
                        (user_id, calendar_id, calendar_name, color, is_primary)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    (
                        user_id,
                        cal["calendar_id"],
                        cal.get("calendar_name", ""),
                        cal.get("color", "#4285F4"),
                        1 if cal.get("is_primary", False) else 0,
                    ),
                )

    def get_selected_calendars(self, user_id: str) -> list[sqlite3.Row]:
        """Fetch the user's selected Google Calendars."""
        return self.fetch_all(
            "SELECT * FROM google_selected_calendars WHERE user_id = ? ORDER BY is_primary DESC, calendar_name",
            (user_id,),
        )

    def get_selected_calendar_ids(self, user_id: str) -> list[str]:
        """Return just the calendar_id strings for the user's selections."""
        rows = self.fetch_all(
            "SELECT calendar_id FROM google_selected_calendars WHERE user_id = ?",
            (user_id,),
        )
        return [row["calendar_id"] for row in rows]

    def delete_selected_calendars(self, user_id: str) -> None:
        """Remove all selected calendars for a user."""
        self.execute(
            "DELETE FROM google_selected_calendars WHERE user_id = ?",
            (user_id,),
        )
        self.commit()

    # ------------------------------------------------------------------
    # Google Calendar Events
    # ------------------------------------------------------------------

    def upsert_google_calendar_event(
        self,
        user_id: str,
        google_event_id: str,
        title: str,
        start_time: str,
        end_time: str,
        event_date: str,
        calendar_id: str,
    ) -> int:
        """Insert or update a synced Google Calendar event."""
        event_id = self._insert_and_get_id(
            """
            INSERT INTO google_calendar_events
                (user_id, google_event_id, title, start_time, end_time,
                 event_date, calendar_id, last_synced_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(user_id, google_event_id, event_date) DO UPDATE SET
                title = excluded.title,
                start_time = excluded.start_time,
                end_time = excluded.end_time,
                calendar_id = excluded.calendar_id,
                last_synced_at = CURRENT_TIMESTAMP
            """,
            (user_id, google_event_id, title, start_time, end_time,
             event_date, calendar_id),
            pk_column="event_id",
        )
        self.commit()
        return event_id

    def mark_task_exported(self, task_id: int, user_id: str) -> None:
        """
        Stamp a task as freshly exported to Google Calendar (its
        Segment identities at this exact moment are now
        reflected there). Used for the "update an existing event" export
        path, where google_event_id doesn't change but the export
        timestamp still needs to move forward — this is what lets the
        app detect a schedule change made AFTER the last export and warn
        the user, instead of silently leaving Google Calendar stale.

        Args:
            task_id: The task that was just (re-)exported.
            user_id: The caller's user_id. The update only takes effect
                if task_id belongs to a plan owned by this user.

        Raises:
            OwnershipError: If task_id doesn't exist or isn't owned by
                user_id.
        """
        result = self.execute(
            """
            UPDATE tasks SET google_exported_at = CURRENT_TIMESTAMP
            WHERE task_id = ?
              AND plan_id IN (SELECT plan_id FROM plans WHERE user_id = ?)
            """,
            (task_id, user_id),
        )
        if result.rowcount == 0:
            self.connection.rollback()
            raise OwnershipError(
                f"Task {task_id} not found or not owned by user {user_id!r}."
            )
        self.commit()

    def get_stale_google_exports(self, plan_id: int, user_id: str) -> list[dict]:
        """
        Find tasks in a plan that were exported to Google Calendar at
        some point, but whose scheduled_start/scheduled_end has changed
        SINCE that export (e.g. the Scheduler was re-run afterwards) —
        meaning Google Calendar currently shows an out-of-date time.

        Tasks never exported at all (google_event_id IS NULL) are not
        "stale" — there's nothing in Google Calendar to be behind yet.

        Args:
            plan_id: The plan to check.
            user_id: The caller's user_id. Returns an empty list (same
                as "plan doesn't exist") if plan_id isn't owned by this
                user.

        Returns:
            List of task rows whose Google Calendar event no longer
            matches the task's current scheduled time.
        """
        return self.fetch_all(
            """
            SELECT t.* FROM tasks t
            JOIN plans p ON t.plan_id = p.plan_id
            WHERE t.plan_id = ?
              AND p.user_id = ?
              AND t.google_event_id IS NOT NULL
              AND (
                    t.google_exported_at IS NULL
                    OR t.updated_at > t.google_exported_at
                  )
            ORDER BY t.order_index ASC, t.task_id ASC
            """,
            (plan_id, user_id),
        )

    def get_google_calendar_events(
        self,
        user_id: str,
        event_date: str,
    ) -> list[sqlite3.Row]:
        """Fetch synced Google Calendar events for a given date."""
        return self.fetch_all(
            """
            SELECT * FROM google_calendar_events
            WHERE user_id = ? AND event_date = ?
            ORDER BY start_time ASC
            """,
            (user_id, event_date),
        )

    def delete_google_events_not_in(
        self,
        user_id: str,
        event_date: str,
        calendar_id: str,
        keep_ids: list[str],
    ) -> None:
        """Remove events deleted from Google for a specific calendar+date."""
        if not keep_ids:
            self.execute(
                """
                DELETE FROM google_calendar_events
                WHERE user_id = ? AND event_date = ? AND calendar_id = ?
                """,
                (user_id, event_date, calendar_id),
            )
        else:
            placeholders = ", ".join("?" for _ in keep_ids)
            self.execute(
                f"""
                DELETE FROM google_calendar_events
                WHERE user_id = ? AND event_date = ? AND calendar_id = ?
                  AND google_event_id NOT IN ({placeholders})
                """,
                (user_id, event_date, calendar_id, *keep_ids),
            )
        self.commit()

    def delete_all_google_calendar_events(self, user_id: str) -> None:
        """Remove all synced events for a user (disconnect cleanup)."""
        self.execute(
            "DELETE FROM google_calendar_events WHERE user_id = ?",
            (user_id,),
        )
        self.commit()

    def delete_google_events_by_calendar(
        self,
        user_id: str,
        calendar_id: str,
    ) -> None:
        """Remove all events from a specific calendar (deselection)."""
        self.execute(
            "DELETE FROM google_calendar_events WHERE user_id = ? AND calendar_id = ?",
            (user_id, calendar_id),
        )
        self.commit()

    # ------------------------------------------------------------------
    # Task Export Tracking
    # ------------------------------------------------------------------

    def update_task_google_event_id(
        self,
        task_id: int,
        user_id: str,
        google_event_id: Optional[str],
    ) -> None:
        """Set the Google Calendar event ID for a newly-exported task,
        and stamp it as freshly exported (see mark_task_exported).

        Raises:
            OwnershipError: If task_id doesn't exist or isn't owned by
                user_id.
        """
        result = self.execute(
            """
            UPDATE tasks
            SET google_event_id = ?, google_exported_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE task_id = ?
              AND plan_id IN (SELECT plan_id FROM plans WHERE user_id = ?)
            """,
            (google_event_id, task_id, user_id),
        )
        if result.rowcount == 0:
            self.connection.rollback()
            raise OwnershipError(
                f"Task {task_id} not found or not owned by user {user_id!r}."
            )
        self.commit()

    def get_tasks_with_google_event_id(
        self,
        plan_id: int,
        user_id: str,
    ) -> list[sqlite3.Row]:
        """Fetch tasks that have been exported to Google Calendar.

        Args:
            plan_id: The plan to check.
            user_id: The caller's user_id. Returns an empty list if
                plan_id isn't owned by this user.
        """
        return self.fetch_all(
            """
            SELECT t.* FROM tasks t
            JOIN plans p ON t.plan_id = p.plan_id
            WHERE t.plan_id = ? AND p.user_id = ? AND t.google_event_id IS NOT NULL
            ORDER BY t.order_index ASC
            """,
            (plan_id, user_id),
        )

    # ------------------------------------------------------------------
    # Segmented Smart Scheduling
    # ------------------------------------------------------------------

    def get_task_segments(self, task_id: int, user_id: str) -> list[sqlite3.Row]:
        """Return a task's ordered segments without leaking cross-tenant rows."""
        return self.fetch_all(
            """
            SELECT s.*, COALESCE(s.scheduled_date,p.plan_date) AS effective_date FROM task_segments s
            JOIN tasks t ON t.task_id = s.task_id
            JOIN plans p ON p.plan_id = t.plan_id
            WHERE s.task_id = ? AND p.user_id = ?
            ORDER BY s.segment_index
            """,
            (task_id, user_id),
        )

    def get_plan_segments(self, plan_id: int, user_id: str) -> list[sqlite3.Row]:
        """Return every segment for an owned plan in chronological order."""
        return self.fetch_all(
            """
            SELECT s.*, COALESCE(s.scheduled_date,p.plan_date) AS effective_date,
                   t.plan_id, t.title, t.is_fixed_time, t.is_break,
                   t.estimated_minutes, t.category_id, t.order_index
            FROM task_segments s
            JOIN tasks t ON t.task_id = s.task_id
            JOIN plans p ON p.plan_id = t.plan_id
            WHERE t.plan_id = ? AND p.user_id = ?
            ORDER BY effective_date, s.scheduled_start, t.order_index, s.segment_index
            """,
            (plan_id, user_id),
        )

    def get_tasks_with_segments(self, plan_id: int, user_id: str) -> list[dict]:
        """Load logical tasks once, each with a nested ordered segment list."""
        result: list[dict] = []
        for row in self.get_tasks_by_plan(plan_id, user_id):
            task = dict(row)
            task["segments"] = [dict(segment) for segment in self.get_task_segments(task["task_id"], user_id)]
            # Never expose a divergent legacy cache as authoritative.  The
            # envelope is projected from segments at the read boundary; zero
            # segments means unscheduled even if an invalid legacy value is
            # intentionally retained on the raw row for diagnostics.
            task["scheduled_start"] = (
                task["segments"][0]["scheduled_start"] if task["segments"] else None
            )
            task["scheduled_end"] = (
                task["segments"][-1]["scheduled_end"] if task["segments"] else None
            )
            result.append(task)
        return result

    def get_segments_by_task_ids(self, task_ids: list[int], user_id: str) -> list[sqlite3.Row]:
        """Batch-load segments for owned logical tasks without duplicating tasks."""
        if not task_ids:
            return []
        placeholders = ",".join("?" for _ in task_ids)
        return self.fetch_all(
            f"""SELECT s.*, COALESCE(s.scheduled_date,p.plan_date) AS effective_date FROM task_segments s
                JOIN tasks t ON t.task_id=s.task_id
                JOIN plans p ON p.plan_id=t.plan_id
                WHERE s.task_id IN ({placeholders}) AND p.user_id=?
                ORDER BY s.task_id,effective_date,s.segment_index""",
            (*task_ids, user_id),
        )

    def _schedule_snapshot(self, plan_id: int, user_id: str) -> dict:
        plan = self.get_plan_by_id(plan_id, user_id)
        if plan is None:
            raise OwnershipError(f"Plan {plan_id} not found or not owned by user {user_id!r}.")
        tasks_state = []
        for task in self.get_tasks_by_plan(plan_id, user_id):
            tasks_state.append({
                "task_id": task["task_id"],
                "scheduled_start": str(task["scheduled_start"]) if task["scheduled_start"] is not None else None,
                "scheduled_end": str(task["scheduled_end"]) if task["scheduled_end"] is not None else None,
            })
        segment_state = []
        for segment in self.get_plan_segments(plan_id, user_id):
            segment_state.append({
                key: (str(segment[key]) if segment[key] is not None else None)
                for key in (
                    "task_id", "segment_index", "scheduled_start", "scheduled_end",
                    "effective_date",
                    "google_event_id", "google_calendar_id", "calendar_provenance",
                    "calendar_sync_status", "calendar_sync_error",
                )
            })
        return {"tasks": tasks_state, "segments": segment_state}

    def replace_plan_segments(
        self,
        plan_id: int,
        user_id: str,
        segments_by_task: dict[int, list[dict]],
        expected_revision: int,
        before_state_override: Optional[dict] = None,
    ) -> str:
        """Atomically replace an owned plan's schedule and record one Undo unit.

        ``segments_by_task`` must contain every logical task in the plan.  An
        empty list explicitly leaves a task unplaced.  Calendar identity is
        retained only when task, segment index, start, and end are unchanged.
        """
        operation_id = uuid.uuid4().hex
        with self.transaction():
            plan = self.get_plan_by_id(plan_id, user_id)
            if plan is None:
                raise OwnershipError(f"Plan {plan_id} not found or not owned by user {user_id!r}.")
            current_revision = int(plan["schedule_revision"] or 0)
            if current_revision != int(expected_revision):
                raise StaleScheduleError(
                    f"Plan {plan_id} changed from revision {expected_revision} to {current_revision}."
                )
            tasks_for_plan = {int(row["task_id"]): row for row in self.get_tasks_by_plan(plan_id, user_id)}
            if set(segments_by_task) != set(tasks_for_plan):
                raise ValueError("A complete plan schedule must include every logical task exactly once.")
            self._validate_segment_payload(segments_by_task, tasks_for_plan)
            before_state = (
                before_state_override
                if before_state_override is not None
                else self._schedule_snapshot(plan_id, user_id)
            )
            old_segments = {
                (int(row["task_id"]), int(row["segment_index"])): row
                for row in self.get_plan_segments(plan_id, user_id)
            }

            revision_update = self.execute(
                """UPDATE plans SET schedule_revision = schedule_revision + 1
                   WHERE plan_id = ? AND user_id = ? AND schedule_revision = ?""",
                (plan_id, user_id, current_revision),
            )
            if revision_update.rowcount != 1:
                raise StaleScheduleError(f"Plan {plan_id} changed while its schedule was being saved.")

            self.execute(
                "DELETE FROM task_segments WHERE task_id IN (SELECT task_id FROM tasks WHERE plan_id = ?)",
                (plan_id,),
            )
            for task_id, proposed in segments_by_task.items():
                ordered = sorted(proposed, key=lambda item: int(item["segment_index"]))
                for expected_index, segment in enumerate(ordered):
                    if int(segment["segment_index"]) != expected_index:
                        raise ValueError(f"Task {task_id} segment indexes must be contiguous from zero.")
                    key = (task_id, expected_index)
                    old = old_segments.get(key)
                    provenance = segment.get("calendar_provenance") or (
                        old["calendar_provenance"] if old is not None else "legacy_unknown"
                    )
                    if provenance not in {"imported", "coachai_created", "legacy_unknown"}:
                        raise ValueError(f"Invalid Calendar provenance: {provenance!r}")
                    sync_status = segment.get(
                        "calendar_sync_status",
                        old["calendar_sync_status"] if old is not None else "not_synced",
                    )
                    if (
                        old is not None
                        and old["calendar_provenance"] == "coachai_created"
                        and (
                            str(old["scheduled_start"]) != str(segment["scheduled_start"])
                            or str(old["scheduled_end"]) != str(segment["scheduled_end"])
                        )
                    ):
                        sync_status = "pending_update"
                    self.execute(
                        """INSERT INTO task_segments
                           (task_id,segment_index,scheduled_start,scheduled_end,
                            scheduled_date,google_event_id,google_calendar_id,calendar_provenance,
                            calendar_sync_status,calendar_sync_error)
                           VALUES (?,?,?,?,?,?,?,?,?,?)""",
                        (
                            task_id, expected_index, segment["scheduled_start"], segment["scheduled_end"],
                            segment.get("scheduled_date") or str(plan["plan_date"]),
                            segment.get("google_event_id", old["google_event_id"] if old is not None else None),
                            segment.get("google_calendar_id", old["google_calendar_id"] if old is not None else None),
                            provenance,
                            sync_status,
                            segment.get("calendar_sync_error", old["calendar_sync_error"] if old is not None else None),
                        ),
                    )
                start_cache = ordered[0]["scheduled_start"] if ordered else None
                end_cache = ordered[-1]["scheduled_end"] if ordered else None
                self._update_task_no_commit(
                    task_id, user_id, scheduled_start=start_cache, scheduled_end=end_cache
                )

            after_state = self._schedule_snapshot(plan_id, user_id)
            self.execute(
                """INSERT INTO schedule_operations
                   (operation_id,plan_id,user_id,base_revision,result_revision,
                    before_state,after_state,status)
                   VALUES (?,?,?,?,?,?,?,'applied')""",
                (
                    operation_id, plan_id, user_id, current_revision, current_revision + 1,
                    json.dumps(before_state, sort_keys=True), json.dumps(after_state, sort_keys=True),
                ),
            )
            payload = self._schedule_notification_payload(before_state, after_state)
            payload.update({
                "plan_id": plan_id,
                "base_revision": current_revision,
                "result_revision": current_revision + 1,
            })
            self._enqueue_notification_event_no_commit(
                user_id=user_id,
                event_type="SCHEDULE_OPERATION_APPLIED",
                scheduled_date=str(plan["plan_date"]),
                schedule_revision=current_revision + 1,
                schedule_operation_id=operation_id,
                payload=payload,
                idempotency_key=f"schedule-applied:{operation_id}",
                bump_sync_revision=True,
            )
        return operation_id

    def _validate_segment_payload(
        self,
        segments_by_task: dict[int, list[dict]],
        tasks_for_plan: dict[int, dict],
    ) -> None:
        """Reject invalid schedules at the persistence boundary.

        The minimum-duration rule applies to newly allocated flexible-task
        segments.  Fixed tasks and preserved historical rows may legitimately
        be shorter and are not retroactively rewritten.
        """
        intervals: list[tuple[time, time, int, bool]] = []
        boundary = WORK_DAY_END
        for task_id, proposed in segments_by_task.items():
            task = tasks_for_plan[task_id]
            is_fixed = bool(task.get("is_fixed_time"))
            if len(proposed) > MAX_SEGMENTS_PER_TASK:
                raise ValueError(f"Task {task_id} exceeds MAX_SEGMENTS_PER_TASK.")
            if is_fixed and len(proposed) != 1:
                raise ValueError(f"Fixed task {task_id} must retain exactly one immutable segment.")

            total_seconds = 0
            task_intervals: list[tuple[time, time]] = []
            ordered = sorted(proposed, key=lambda item: int(item.get("segment_index", -1)))
            for expected_index, segment in enumerate(ordered):
                if int(segment.get("segment_index", -1)) != expected_index:
                    raise ValueError(f"Task {task_id} segment indexes must be contiguous from zero.")
                start = self._parse_legacy_time(segment.get("scheduled_start"))
                end = self._parse_legacy_time(segment.get("scheduled_end"))
                if start is None or end is None or end <= start or end > boundary:
                    raise ValueError(f"Task {task_id} contains an invalid same-day segment.")
                seconds = int(
                    (datetime.combine(date.min, end) - datetime.combine(date.min, start)).total_seconds()
                )
                if not is_fixed and seconds < MIN_SEGMENT_MINUTES * 60:
                    raise ValueError(f"Task {task_id} contains a newly allocated segment below the minimum.")
                if task_intervals and start < task_intervals[-1][1]:
                    raise ValueError(f"Task {task_id} contains overlapping segments.")
                task_intervals.append((start, end))
                intervals.append((start, end, task_id, is_fixed))
                total_seconds += seconds

            if proposed and not is_fixed and total_seconds != int(task["estimated_minutes"]) * 60:
                raise ValueError(f"Task {task_id} segments do not preserve its estimated duration.")
            if is_fixed:
                cached_start = self._parse_legacy_time(task.get("scheduled_start"))
                cached_end = self._parse_legacy_time(task.get("scheduled_end"))
                if cached_start is None or cached_end is None:
                    raise ValueError(f"Fixed task {task_id} has no valid immutable window.")
                if task_intervals[0] != (cached_start, cached_end):
                    raise ValueError(f"Fixed task {task_id} cannot be moved by Smart Scheduling.")

        intervals.sort(key=lambda item: (item[0], item[1], item[2]))
        for index, (start, end, task_id, fixed) in enumerate(intervals):
            for other_start, other_end, other_id, other_fixed in intervals[index + 1:]:
                if other_start >= end:
                    break
                if task_id != other_id and start < other_end and not (fixed and other_fixed):
                    raise ValueError(f"Tasks {task_id} and {other_id} overlap illegally.")

    def get_plans_in_range(self, user_id: str, start_date: date, end_date: date) -> list[sqlite3.Row]:
        """Return tenant-owned daily plans in an inclusive local-date range."""
        return self.fetch_all(
            """SELECT * FROM plans WHERE user_id=? AND plan_date BETWEEN ? AND ?
               ORDER BY plan_date,plan_id""",
            (user_id, start_date.isoformat(), end_date.isoformat()),
        )

    def get_future_planning_tasks(
        self, user_id: str, start_date: date, end_date: date,
    ) -> list[dict]:
        """Load each logical task once with date-aware physical segments."""
        plans_in_scope = self.get_plans_in_range(user_id, start_date, end_date)
        result: list[dict] = []
        for plan in plans_in_scope:
            for row in self.get_tasks_by_plan(int(plan["plan_id"]), user_id):
                task = dict(row)
                task["plan_date"] = str(plan["plan_date"])
                task["schedule_revision"] = int(plan["schedule_revision"] or 0)
                task["segments"] = [dict(s) for s in self.get_task_segments(int(task["task_id"]), user_id)]
                result.append(task)
        return result

    def get_segments_in_date_range(
        self, user_id: str, start_date: date, end_date: date,
    ) -> list[sqlite3.Row]:
        """Load tenant-owned physical occupancy by effective local date."""
        return self.fetch_all(
            """SELECT s.*,COALESCE(s.scheduled_date,p.plan_date) AS effective_date,
                      t.plan_id,t.is_fixed_time,t.is_break,t.status,t.started_at
               FROM task_segments s JOIN tasks t ON t.task_id=s.task_id
               JOIN plans p ON p.plan_id=t.plan_id
               WHERE p.user_id=? AND COALESCE(s.scheduled_date,p.plan_date) BETWEEN ? AND ?
               ORDER BY effective_date,s.scheduled_start,s.task_id,s.segment_index""",
            (user_id, start_date.isoformat(), end_date.isoformat()),
        )

    def _future_schedule_snapshot(
        self, plan_ids: list[int], task_ids: list[int], user_id: str,
    ) -> dict:
        revisions = {}
        for plan_id in plan_ids:
            plan = self.get_plan_by_id(plan_id, user_id)
            if plan is None:
                raise OwnershipError("Future-planning scope is not owned by this user.")
            revisions[str(plan_id)] = int(plan["schedule_revision"] or 0)
        task_state = []
        segment_state = []
        for task_id in sorted(task_ids):
            task = self.get_task(task_id, user_id)
            if task is None:
                raise OwnershipError("A future-planning task is not owned by this user.")
            task_state.append({
                "task_id": task_id,
                "scheduled_start": str(task["scheduled_start"]) if task["scheduled_start"] is not None else None,
                "scheduled_end": str(task["scheduled_end"]) if task["scheduled_end"] is not None else None,
                "estimated_minutes": int(task["estimated_minutes"]),
                "priority": int(task["priority"]),
                "earliest_start": str(task["earliest_start"]) if task["earliest_start"] is not None else None,
                "deadline": str(task["deadline"]) if task["deadline"] is not None else None,
                "status": str(task["status"]),
                "actual_minutes": task["actual_minutes"],
                "started_at": str(task["started_at"]) if task["started_at"] is not None else None,
            })
            for segment in self.get_task_segments(task_id, user_id):
                segment_state.append({
                    "task_id": task_id,
                    "segment_index": int(segment["segment_index"]),
                    "scheduled_start": str(segment["scheduled_start"]),
                    "scheduled_end": str(segment["scheduled_end"]),
                    "scheduled_date": str(segment["effective_date"]),
                    "google_event_id": segment["google_event_id"],
                    "google_calendar_id": segment["google_calendar_id"],
                    "calendar_provenance": segment["calendar_provenance"],
                    "calendar_sync_status": segment["calendar_sync_status"],
                    "calendar_sync_error": segment["calendar_sync_error"],
                })
        return {
            "kind": "future_planning_v1", "plan_revisions": revisions,
            "tasks": task_state, "segments": segment_state,
        }

    def apply_future_plan(
        self,
        user_id: str,
        segments_by_task: dict[int, list[dict]],
        expected_revisions: dict[int, int],
        expected_task_states: dict[int, dict],
        task_updates: Optional[dict[int, dict]] = None,
        *,
        draft_tasks: Optional[dict[str, dict]] = None,
        draft_segments: Optional[dict[str, list[dict]]] = None,
        anchor_plan_date: Optional[date] = None,
        expected_anchor_plan_id: Optional[int] = None,
    ) -> str:
        """Atomically materialize drafts and persist one future-plan Undo unit."""
        drafts = {str(key): dict(value) for key, value in (draft_tasks or {}).items()}
        draft_payloads = {str(key): list(value) for key, value in (draft_segments or {}).items()}
        if set(drafts) != set(draft_payloads):
            raise ValueError("Every draft task requires exactly one preview segment payload.")
        if not expected_revisions and not drafts:
            raise ValueError("A future plan requires an owned plan or at least one draft task.")
        if drafts and anchor_plan_date is None:
            raise ValueError("Draft tasks require an anchor planning date.")
        operation_id = uuid.uuid4().hex
        effective_revisions = {int(key): int(value) for key, value in expected_revisions.items()}
        plan_ids = sorted(effective_revisions)
        normalized_updates = {int(key): dict(value) for key, value in (task_updates or {}).items()}
        existing_task_ids = sorted(int(value) for value in segments_by_task)
        if set(normalized_updates) - set(existing_task_ids):
            raise ValueError("Task constraints may only be updated for tasks in the future plan.")
        with self.transaction():
            if self.fetch_one("SELECT user_id FROM users WHERE user_id=?", (user_id,)) is None:
                raise OwnershipError("Future-planning user does not exist.")
            for plan_id in list(plan_ids):
                plan = self.get_plan_by_id(plan_id, user_id)
                if plan is None:
                    raise OwnershipError("Future-planning scope is not owned by this user.")
                actual = int(plan["schedule_revision"] or 0)
                if actual != effective_revisions[plan_id]:
                    raise StaleScheduleError(
                        f"Plan {plan_id} changed from revision {effective_revisions[plan_id]} to {actual}."
                    )

            created_plan_ids: list[int] = []
            anchor_plan_id = expected_anchor_plan_id
            if drafts:
                current_anchor = self.get_plan_by_date(user_id, anchor_plan_date)
                if expected_anchor_plan_id is None:
                    if current_anchor is not None:
                        raise StaleScheduleError(
                            "The anchor day was created after Preview; calculate a new preview."
                        )
                    anchor_plan_id = self._insert_and_get_id(
                        """INSERT INTO plans (user_id,plan_date,raw_input,status)
                           VALUES (?,?,?,'active')""",
                        (user_id, anchor_plan_date.isoformat(), "Future Planning"),
                        pk_column="plan_id",
                    )
                    created_plan_ids.append(int(anchor_plan_id))
                    effective_revisions[int(anchor_plan_id)] = 0
                    plan_ids = sorted(set(plan_ids) | {int(anchor_plan_id)})
                elif current_anchor is None or int(current_anchor["plan_id"]) != int(expected_anchor_plan_id):
                    raise StaleScheduleError(
                        "The anchor day changed after Preview; calculate a new preview."
                    )
                elif int(expected_anchor_plan_id) not in effective_revisions:
                    raise StaleScheduleError("The anchor plan revision was not captured by Preview.")

            tasks_for_plan: dict[int, dict] = {}
            for task_id in existing_task_ids:
                row = self.get_task(task_id, user_id)
                if row is None:
                    raise StaleScheduleError("A task was deleted after the preview was calculated.")
                task = dict(row)
                expected = expected_task_states.get(task_id)
                actual_state = {
                    "status": task["status"], "estimated_minutes": int(task["estimated_minutes"]),
                    "actual_minutes": task["actual_minutes"], "started_at": str(task["started_at"]) if task["started_at"] else None,
                    "priority": int(task["priority"]),
                    "earliest_start": str(task.get("earliest_start")) if task.get("earliest_start") else None,
                    "deadline": str(task.get("deadline")) if task.get("deadline") else None,
                }
                if expected != actual_state:
                    raise StaleScheduleError(f"Task {task_id} changed after preview calculation.")
                if int(task["plan_id"]) not in effective_revisions:
                    raise OwnershipError("A proposed task is outside the owned planning scope.")
                update = normalized_updates.get(task_id, {})
                unknown = set(update) - {"estimated_minutes", "priority", "earliest_start", "deadline"}
                if unknown:
                    raise ValueError(f"Unsupported future-planning task update: {', '.join(sorted(unknown))}.")
                if "estimated_minutes" in update:
                    update["estimated_minutes"] = int(update["estimated_minutes"])
                    if update["estimated_minutes"] < int(task["actual_minutes"] or 0):
                        raise ValueError("Estimated minutes cannot be less than completed work.")
                if "priority" in update:
                    update["priority"] = int(update["priority"])
                    if not 1 <= update["priority"] <= 5:
                        raise ValueError("Priority must be between 1 and 5.")
                for field in ("earliest_start", "deadline"):
                    if field in update:
                        parsed = self._parse_local_planning_datetime(update[field])
                        self._validate_user_local_planning_datetime(user_id, parsed)
                        update[field] = parsed.isoformat(sep=" ") if parsed else None
                earliest = self._parse_local_planning_datetime(update.get("earliest_start", task.get("earliest_start")))
                deadline = self._parse_local_planning_datetime(update.get("deadline", task.get("deadline")))
                if earliest and deadline and earliest > deadline:
                    raise ValueError("earliest_start must be on or before deadline.")
                task.update(update)
                tasks_for_plan[task_id] = task

            before = self._future_schedule_snapshot(plan_ids, existing_task_ids, user_id)
            before["created_plan_ids"] = created_plan_ids

            draft_id_map: dict[str, int] = {}
            if drafts:
                order_row = self.fetch_one(
                    "SELECT COALESCE(MAX(order_index),-1) AS max_order FROM tasks WHERE plan_id=?",
                    (anchor_plan_id,),
                )
                next_order = int(order_row["max_order"]) + 1
                for offset, (temporary_id, draft) in enumerate(drafts.items()):
                    title = str(draft.get("title") or "").strip()
                    description = draft.get("description")
                    remaining = int(draft.get("remaining_minutes") or 0)
                    priority = int(draft.get("priority") or 3)
                    if not title or remaining <= 0:
                        raise ValueError("Every draft task requires a title and positive duration.")
                    if not 1 <= priority <= 5:
                        raise ValueError("Priority must be between 1 and 5.")
                    earliest = self._parse_local_planning_datetime(draft.get("earliest_start"))
                    deadline = self._parse_local_planning_datetime(draft.get("deadline"))
                    self._validate_user_local_planning_datetime(user_id, earliest)
                    self._validate_user_local_planning_datetime(user_id, deadline)
                    if earliest and deadline and earliest > deadline:
                        raise ValueError("earliest_start must be on or before deadline.")
                    task_id = int(self._insert_and_get_id(
                        """INSERT INTO tasks
                           (plan_id,title,description,priority,estimated_minutes,order_index,
                            is_fixed_time,is_break,earliest_start,deadline)
                           VALUES (?,?,?,?,?,?,0,0,?,?)""",
                        (
                            anchor_plan_id, title, description, priority, remaining,
                            next_order + offset,
                            earliest.isoformat(sep=" ") if earliest else None,
                            deadline.isoformat(sep=" ") if deadline else None,
                        ),
                        pk_column="task_id",
                    ))
                    draft_id_map[temporary_id] = task_id
                    tasks_for_plan[task_id] = dict(self.get_task(task_id, user_id))

            combined_segments = {int(key): list(value) for key, value in segments_by_task.items()}
            for temporary_id, payload in draft_payloads.items():
                combined_segments[draft_id_map[temporary_id]] = payload
            all_task_ids = sorted(combined_segments)
            self._validate_future_segment_payload(combined_segments, tasks_for_plan, user_id)
            before["created_task_ids"] = sorted(draft_id_map.values())
            before["draft_task_ids"] = draft_id_map
            old_segments = {
                (int(s["task_id"]), int(s["segment_index"])): s
                for task_id in existing_task_ids for s in self.get_task_segments(task_id, user_id)
            }
            for plan_id in plan_ids:
                expected = effective_revisions[plan_id]
                updated = self.execute(
                    """UPDATE plans SET schedule_revision=schedule_revision+1
                       WHERE plan_id=? AND user_id=? AND schedule_revision=?""",
                    (plan_id, user_id, expected),
                )
                if updated.rowcount != 1:
                    raise StaleScheduleError("The future schedule changed while it was being saved.")
            for task_id, update in normalized_updates.items():
                self._update_task_no_commit(task_id, user_id, **update)
            for task_id, proposed in combined_segments.items():
                self.execute("DELETE FROM task_segments WHERE task_id=?", (task_id,))
                ordered = sorted(proposed, key=lambda item: int(item["segment_index"]))
                for segment in ordered:
                    key = (task_id, int(segment["segment_index"]))
                    old = old_segments.get(key)
                    unchanged = old is not None and (
                        str(old["effective_date"]) == str(segment["scheduled_date"])
                        and
                        str(old["scheduled_start"]) == str(segment["scheduled_start"])
                        and str(old["scheduled_end"]) == str(segment["scheduled_end"])
                    )
                    owned = (
                        old is not None
                        and old["calendar_provenance"] == "coachai_created"
                        and old["google_event_id"]
                    )
                    preserve_identity = unchanged or owned
                    provenance = old["calendar_provenance"] if preserve_identity else "legacy_unknown"
                    event_id = old["google_event_id"] if preserve_identity else None
                    calendar_id = old["google_calendar_id"] if preserve_identity else None
                    sync_status = (
                        old["calendar_sync_status"] if unchanged
                        else ("pending_update" if owned else "not_synced")
                    )
                    self.execute(
                        """INSERT INTO task_segments
                           (task_id,segment_index,scheduled_start,scheduled_end,scheduled_date,
                            google_event_id,google_calendar_id,calendar_provenance,calendar_sync_status)
                           VALUES (?,?,?,?,?,?,?,?,?)""",
                        (task_id, int(segment["segment_index"]), segment["scheduled_start"],
                         segment["scheduled_end"], str(segment["scheduled_date"]), event_id,
                         calendar_id, provenance, sync_status),
                    )
                self._update_task_no_commit(
                    task_id, user_id,
                    scheduled_start=ordered[0]["scheduled_start"] if ordered else None,
                    scheduled_end=ordered[-1]["scheduled_end"] if ordered else None,
                )
            after = self._future_schedule_snapshot(plan_ids, all_task_ids, user_id)
            after["created_plan_ids"] = created_plan_ids
            after["created_task_ids"] = sorted(draft_id_map.values())
            after["draft_task_ids"] = draft_id_map
            anchor = int(anchor_plan_id) if drafts else plan_ids[0]
            self.execute(
                """INSERT INTO schedule_operations
                   (operation_id,plan_id,user_id,base_revision,result_revision,before_state,after_state,status)
                   VALUES (?,?,?,?,?,?,?,'applied')""",
                (operation_id, anchor, user_id, effective_revisions[anchor],
                 effective_revisions[anchor] + 1, json.dumps(before, sort_keys=True),
                  json.dumps(after, sort_keys=True)),
            )
            payload = self._schedule_notification_payload(before, after)
            payload.update({
                "plan_ids": plan_ids,
                "created_task_ids": sorted(draft_id_map.values()),
                "base_revisions": effective_revisions,
                "result_revisions": {
                    plan_id: effective_revisions[plan_id] + 1 for plan_id in plan_ids
                },
            })
            self._enqueue_notification_event_no_commit(
                user_id=user_id,
                event_type="SCHEDULE_OPERATION_APPLIED",
                schedule_revision=max(effective_revisions.values()) + 1,
                schedule_operation_id=operation_id,
                payload=payload,
                idempotency_key=f"schedule-applied:{operation_id}",
                bump_sync_revision=True,
            )
        return operation_id

    def _validate_future_segment_payload(
        self, segments_by_task: dict[int, list[dict]], tasks_by_id: dict[int, dict], user_id: str,
    ) -> None:
        """Enforce multi-day invariants at the persistence boundary."""
        proposed_ids = set(segments_by_task)
        occupied: dict[str, list[tuple[time, time, int]]] = {}
        rows = self.fetch_all(
            """SELECT s.*,COALESCE(s.scheduled_date,p.plan_date) effective_date
               FROM task_segments s JOIN tasks t ON t.task_id=s.task_id
               JOIN plans p ON p.plan_id=t.plan_id WHERE p.user_id=?""", (user_id,),
        )
        for row in rows:
            if int(row["task_id"]) not in proposed_ids:
                occupied.setdefault(str(row["effective_date"]), []).append((
                    self._parse_legacy_time(row["scheduled_start"]),
                    self._parse_legacy_time(row["scheduled_end"]), int(row["task_id"])))
        for task_id, proposed in segments_by_task.items():
            task = tasks_by_id[task_id]
            if bool(task["is_fixed_time"]) or task["status"] in {"completed", "failed"} or task["started_at"]:
                raise ValueError(f"Task {task_id} is protected and cannot be future-planned.")
            if len(proposed) > MAX_SEGMENTS_PER_TASK:
                raise ValueError(f"Task {task_id} exceeds MAX_SEGMENTS_PER_TASK.")
            remaining = max(0, int(task["estimated_minutes"]) - int(task["actual_minutes"] or 0))
            total = 0
            last = None
            for expected_index, segment in enumerate(sorted(proposed, key=lambda x: int(x["segment_index"]))):
                if int(segment["segment_index"]) != expected_index:
                    raise ValueError("Future segment indexes must be contiguous from zero.")
                day = date.fromisoformat(str(segment["scheduled_date"]))
                start = self._parse_legacy_time(segment["scheduled_start"])
                end = self._parse_legacy_time(segment["scheduled_end"])
                if start is None or end is None or end <= start or end > WORK_DAY_END:
                    raise ValueError(f"Task {task_id} contains an invalid same-day segment.")
                duration = int((datetime.combine(day, end) - datetime.combine(day, start)).total_seconds() // 60)
                if duration < MIN_SEGMENT_MINUTES:
                    raise ValueError(f"Task {task_id} contains a segment below the minimum.")
                start_dt, end_dt = datetime.combine(day, start), datetime.combine(day, end)
                if task.get("earliest_start") and start_dt < datetime.fromisoformat(str(task["earliest_start"])):
                    raise ValueError(f"Task {task_id} violates earliest_start.")
                if task.get("deadline") and end_dt > datetime.fromisoformat(str(task["deadline"])):
                    raise ValueError(f"Task {task_id} violates its deadline.")
                if last is not None and start_dt < last:
                    raise ValueError(f"Task {task_id} has overlapping or unordered segments.")
                for other_start, other_end, other_id in occupied.setdefault(day.isoformat(), []):
                    if start < other_end and end > other_start:
                        raise ValueError(f"Task {task_id} overlaps protected task {other_id}.")
                occupied[day.isoformat()].append((start, end, task_id))
                last, total = end_dt, total + duration
            if proposed and total != remaining:
                raise ValueError(f"Task {task_id} segments do not equal remaining work ({remaining} minutes).")

    def undo_schedule_operation(self, operation_id: str, user_id: str) -> None:
        """Restore one complete Smart Scheduling operation in one transaction."""
        with self.transaction():
            operation = self.fetch_one(
                """SELECT so.* FROM schedule_operations so
                   JOIN plans p ON p.plan_id=so.plan_id
                   WHERE so.operation_id=? AND so.user_id=? AND p.user_id=?""",
                (operation_id, user_id, user_id),
            )
            if operation is None:
                raise OwnershipError("Scheduling operation not found or not owned by this user.")
            if operation["status"] != "applied":
                raise StaleScheduleError("This scheduling operation has already been undone.")
            state = json.loads(operation["before_state"])
            if state.get("kind") == "future_planning_v1":
                self._undo_future_schedule_operation(operation, state, user_id)
                return
            plan = self.get_plan_by_id(int(operation["plan_id"]), user_id)
            if int(plan["schedule_revision"] or 0) != int(operation["result_revision"]):
                raise StaleScheduleError("The plan changed after this operation; Undo would overwrite newer work.")
            plan_id = int(operation["plan_id"])
            owned_ids = {int(row["task_id"]) for row in self.get_tasks_by_plan(plan_id, user_id)}
            snapshot_ids = {int(row["task_id"]) for row in state["tasks"]}
            created_ids = {int(task_id) for task_id in state.get("created_task_ids", [])}
            if owned_ids != snapshot_ids | created_ids:
                raise StaleScheduleError("The plan's task set changed after this operation.")
            self.execute(
                "DELETE FROM task_segments WHERE task_id IN (SELECT task_id FROM tasks WHERE plan_id=?)",
                (plan_id,),
            )
            if created_ids:
                placeholders = ",".join("?" for _ in created_ids)
                self.execute(
                    f"""DELETE FROM tasks WHERE task_id IN ({placeholders})
                        AND plan_id=? AND plan_id IN (
                            SELECT plan_id FROM plans WHERE user_id=?
                        )""",
                    (*sorted(created_ids), plan_id, user_id),
                )
            for segment in state["segments"]:
                self.execute(
                    """INSERT INTO task_segments
                       (task_id,segment_index,scheduled_start,scheduled_end,
                        scheduled_date,google_event_id,google_calendar_id,calendar_provenance,
                        calendar_sync_status,calendar_sync_error)
                       VALUES (?,?,?,?,?,?,?,?,?,?)""",
                    tuple(segment.get(key) for key in (
                        "task_id", "segment_index", "scheduled_start", "scheduled_end",
                        "effective_date",
                        "google_event_id", "google_calendar_id", "calendar_provenance",
                        "calendar_sync_status", "calendar_sync_error",
                    )),
                )
            for task in state["tasks"]:
                self._update_task_no_commit(
                    int(task["task_id"]), user_id,
                    scheduled_start=task["scheduled_start"], scheduled_end=task["scheduled_end"],
                )
            # Google is an external system and cannot participate in this DB
            # transaction.  Restored CoachAI-owned identities are therefore
            # explicitly retryable until the next reconciliation confirms
            # their remote window matches the restored segment.
            self.execute(
                """UPDATE task_segments SET calendar_sync_status='pending_update'
                   WHERE task_id IN (SELECT task_id FROM tasks WHERE plan_id=?)
                     AND calendar_provenance='coachai_created'
                     AND google_event_id IS NOT NULL""",
                (plan_id,),
            )
            self.execute(
                "UPDATE plans SET schedule_revision=schedule_revision+1 WHERE plan_id=? AND user_id=?",
                (plan_id, user_id),
            )
            self.execute(
                """UPDATE schedule_operations SET status='undone', undone_at=CURRENT_TIMESTAMP
                   WHERE operation_id=? AND status='applied'""",
                (operation_id,),
            )
            restored_revision = int(operation["result_revision"]) + 1
            after_state = json.loads(operation["after_state"])
            payload = self._schedule_notification_payload(after_state, state)
            payload.update({"plan_id": plan_id, "restored_revision": restored_revision})
            self._enqueue_notification_event_no_commit(
                user_id=user_id,
                event_type="SCHEDULE_OPERATION_UNDONE",
                scheduled_date=str(plan["plan_date"]),
                schedule_revision=restored_revision,
                schedule_operation_id=operation_id,
                payload=payload,
                idempotency_key=f"schedule-undone:{operation_id}",
                bump_sync_revision=True,
            )

    def _undo_future_schedule_operation(self, operation, state: dict, user_id: str) -> None:
        """Restore the exact multi-plan snapshot without rerunning the planner."""
        after = json.loads(operation["after_state"])
        expected_revisions = {int(k): int(v) for k, v in after["plan_revisions"].items()}
        for plan_id, expected in expected_revisions.items():
            plan = self.get_plan_by_id(plan_id, user_id)
            if plan is None or int(plan["schedule_revision"] or 0) != expected:
                raise StaleScheduleError(
                    "The future schedule changed after this operation; Undo would overwrite newer work."
                )
        task_ids = {int(row["task_id"]) for row in state["tasks"]}
        created_task_ids = {int(value) for value in state.get("created_task_ids", [])}
        all_task_ids = task_ids | created_task_ids
        if any(self.get_task(task_id, user_id) is None for task_id in all_task_ids):
            raise StaleScheduleError("A future-planned task changed or was deleted after this operation.")
        after_tasks = {int(row["task_id"]): row for row in after.get("tasks", [])}
        for task_id in created_task_ids:
            current = dict(self.get_task(task_id, user_id))
            expected = after_tasks.get(task_id, {})
            for field in (
                "estimated_minutes", "priority", "earliest_start", "deadline",
                "status", "actual_minutes", "started_at",
            ):
                current_value = current.get(field)
                if field in {"estimated_minutes", "priority"}:
                    current_value = int(current_value)
                elif current_value is not None:
                    current_value = str(current_value)
                if current_value != expected.get(field):
                    raise StaleScheduleError(
                        "A newly created future task changed after Apply; Undo would lose progress."
                    )
        for task_id in all_task_ids:
            self.execute("DELETE FROM task_segments WHERE task_id=?", (task_id,))
        for segment in state["segments"]:
            self.execute(
                """INSERT INTO task_segments
                   (task_id,segment_index,scheduled_start,scheduled_end,scheduled_date,
                    google_event_id,google_calendar_id,calendar_provenance,
                    calendar_sync_status,calendar_sync_error)
                   VALUES (?,?,?,?,?,?,?,?,?,?)""",
                tuple(segment.get(key) for key in (
                    "task_id", "segment_index", "scheduled_start", "scheduled_end", "scheduled_date",
                    "google_event_id", "google_calendar_id", "calendar_provenance",
                    "calendar_sync_status", "calendar_sync_error",
                )),
            )
        for task in state["tasks"]:
            restored = {
                "scheduled_start": task["scheduled_start"],
                "scheduled_end": task["scheduled_end"],
            }
            # Patch 5 operations created before structured requests contain
            # only the compatibility cache.  Restore newer constraint fields
            # when present while remaining able to Undo those older records.
            for field in ("estimated_minutes", "priority", "earliest_start", "deadline"):
                if field in task:
                    restored[field] = task[field]
            self._update_task_no_commit(int(task["task_id"]), user_id, **restored)
        for task_id in sorted(created_task_ids):
            deleted = self.execute(
                """DELETE FROM tasks WHERE task_id=? AND plan_id IN
                   (SELECT plan_id FROM plans WHERE user_id=?)""",
                (task_id, user_id),
            )
            if deleted.rowcount != 1:
                raise StaleScheduleError("A newly created future task could not be removed safely.")
        self.execute(
            """UPDATE task_segments SET calendar_sync_status='pending_update'
               WHERE task_id IN (
                   SELECT t.task_id FROM tasks t JOIN plans p ON p.plan_id=t.plan_id
                   WHERE p.user_id=?
               ) AND calendar_provenance='coachai_created' AND google_event_id IS NOT NULL""",
            (user_id,),
        )
        for plan_id, expected in expected_revisions.items():
            updated = self.execute(
                """UPDATE plans SET schedule_revision=schedule_revision+1
                   WHERE plan_id=? AND user_id=? AND schedule_revision=?""",
                (plan_id, user_id, expected),
            )
            if updated.rowcount != 1:
                raise StaleScheduleError("A future plan changed during Undo.")
        self.execute(
            """UPDATE schedule_operations SET status='undone',undone_at=CURRENT_TIMESTAMP
               WHERE operation_id=? AND user_id=? AND status='applied'""",
            (operation["operation_id"], user_id),
        )
        payload = self._schedule_notification_payload(after, state)
        payload.update({
            "plan_ids": sorted(expected_revisions),
            "restored_revisions": {
                plan_id: expected + 1 for plan_id, expected in expected_revisions.items()
            },
        })
        self._enqueue_notification_event_no_commit(
            user_id=user_id,
            event_type="SCHEDULE_OPERATION_UNDONE",
            schedule_revision=max(expected_revisions.values()) + 1,
            schedule_operation_id=str(operation["operation_id"]),
            payload=payload,
            idempotency_key=f"schedule-undone:{operation['operation_id']}",
            bump_sync_revision=True,
        )

    def update_segment_calendar_state(
        self,
        segment_id: int,
        user_id: str,
        *,
        google_event_id: Optional[str] = None,
        google_calendar_id: Optional[str] = None,
        calendar_provenance: Optional[str] = None,
        calendar_sync_status: Optional[str] = None,
        calendar_sync_error: Optional[str] = None,
    ) -> None:
        """Update Calendar state for an owned segment only."""
        if calendar_provenance is not None and calendar_provenance not in {
            "imported", "coachai_created", "legacy_unknown"
        }:
            raise ValueError(f"Invalid Calendar provenance: {calendar_provenance!r}")
        values = {
            "google_event_id": google_event_id,
            "google_calendar_id": google_calendar_id,
            "calendar_provenance": calendar_provenance,
            "calendar_sync_status": calendar_sync_status,
            "calendar_sync_error": calendar_sync_error,
        }
        supplied = {key: value for key, value in values.items() if value is not None}
        # Clearing an old error is a real update and is represented by an
        # empty string; callers never need to infer ownership from an ID.
        if not supplied:
            return
        with self.transaction():
            before = self.fetch_one(
                """SELECT s.*,t.title,p.plan_id,p.schedule_revision,
                          COALESCE(s.scheduled_date,p.plan_date) effective_date
                   FROM task_segments s
                   JOIN tasks t ON t.task_id=s.task_id
                   JOIN plans p ON p.plan_id=t.plan_id
                   WHERE s.segment_id=? AND p.user_id=?""",
                (segment_id, user_id),
            )
            if before is None:
                raise OwnershipError("Segment not found or not owned by this user.")
            result = self.execute(
                f"""UPDATE task_segments SET {', '.join(f'{key}=?' for key in supplied)}
                    WHERE segment_id=? AND task_id IN (
                        SELECT t.task_id FROM tasks t JOIN plans p ON p.plan_id=t.plan_id
                        WHERE p.user_id=?
                    )""",
                (*supplied.values(), segment_id, user_id),
            )
            if result.rowcount != 1:
                raise OwnershipError("Segment not found or not owned by this user.")

            previous_status = str(before["calendar_sync_status"] or "")
            previous_error = str(before["calendar_sync_error"] or "")
            next_status = str(supplied.get("calendar_sync_status", previous_status) or "")
            next_error = str(supplied.get("calendar_sync_error", previous_error) or "")
            was_attention = bool(previous_error) or "error" in previous_status or "failed" in previous_status
            needs_attention = bool(next_error) or "error" in next_status or "failed" in next_status
            event_type = None
            if needs_attention and (not was_attention or next_error != previous_error):
                event_type = "CALENDAR_ATTENTION_REQUIRED"
            elif was_attention and not needs_attention and next_status == "synced":
                event_type = "CALENDAR_RECONCILED"
            if event_type:
                self._enqueue_notification_event_no_commit(
                    user_id=user_id,
                    event_type=event_type,
                    task_id=int(before["task_id"]),
                    segment_id=segment_id,
                    scheduled_date=str(before["effective_date"]),
                    segment_index=int(before["segment_index"]),
                    schedule_revision=int(before["schedule_revision"] or 0),
                    payload={
                        "task_title": str(before["title"]),
                        "plan_id": int(before["plan_id"]),
                        "calendar_sync_status": next_status,
                        "calendar_sync_error": next_error or None,
                    },
                    idempotency_key=(
                        f"calendar-segment:{event_type}:{before['task_id']}:"
                        f"{before['effective_date']}:{before['segment_index']}:"
                        f"{before['schedule_revision']}"
                    ),
                    bump_sync_revision=False,
                )

    def get_schedule_operation(self, operation_id: str, user_id: str) -> Optional[sqlite3.Row]:
        return self.fetch_one(
            "SELECT * FROM schedule_operations WHERE operation_id=? AND user_id=?",
            (operation_id, user_id),
        )

    def count_stale_segment_exports(self, plan_id: int, user_id: str) -> int:
        row = self.fetch_one(
            """SELECT COUNT(*) AS n FROM task_segments s
               JOIN tasks t ON t.task_id=s.task_id
               JOIN plans p ON p.plan_id=t.plan_id
               WHERE t.plan_id=? AND p.user_id=?
                 AND s.calendar_provenance='coachai_created'
                 AND s.google_event_id IS NOT NULL
                 AND s.calendar_sync_status!='synced'""",
            (plan_id, user_id),
        )
        return int(row["n"] if row else 0)

    def mark_schedule_calendar_reconciliation(
        self, operation_id: str, user_id: str, error: Optional[str] = None,
    ) -> None:
        with self.transaction():
            before = self.get_schedule_operation(operation_id, user_id)
            if before is None:
                raise OwnershipError("Scheduling operation not found or not owned by this user.")
            result = self.execute(
                """UPDATE schedule_operations
                   SET calendar_reconciled_at=CASE WHEN ? IS NULL THEN CURRENT_TIMESTAMP ELSE NULL END,
                       calendar_reconciliation_error=?
                   WHERE operation_id=? AND user_id=?""",
                (error, error, operation_id, user_id),
            )
            if result.rowcount != 1:
                raise OwnershipError("Scheduling operation not found or not owned by this user.")
            previous_error = str(before["calendar_reconciliation_error"] or "")
            event_type = None
            if error and error != previous_error:
                event_type = "CALENDAR_ATTENTION_REQUIRED"
            elif previous_error and error is None:
                event_type = "CALENDAR_RECONCILED"
            if event_type:
                self._enqueue_notification_event_no_commit(
                    user_id=user_id,
                    event_type=event_type,
                    schedule_revision=int(before["result_revision"] or 0),
                    schedule_operation_id=operation_id,
                    payload={
                        "plan_id": int(before["plan_id"]),
                        "calendar_reconciliation_error": error,
                    },
                    idempotency_key=f"calendar-operation:{event_type}:{operation_id}",
                    bump_sync_revision=False,
                )

    def set_single_task_segment(
        self, task_id: int, user_id: str, scheduled_start: str, scheduled_end: str,
    ) -> None:
        """Atomically set one manual/fixed window and invalidate stale schedules."""
        start = self._parse_legacy_time(scheduled_start)
        end = self._parse_legacy_time(scheduled_end)
        if start is None or end is None or end <= start:
            raise ValueError("Task interval must be valid and same-day.")
        with self.transaction():
            task = self.get_task(task_id, user_id)
            if task is None:
                raise OwnershipError("Task not found or not owned by this user.")
            old = self.fetch_one(
                "SELECT * FROM task_segments WHERE task_id=? AND segment_index=0", (task_id,)
            )
            self.execute("DELETE FROM task_segments WHERE task_id=?", (task_id,))
            self.execute(
                """INSERT INTO task_segments
                   (task_id,segment_index,scheduled_start,scheduled_end,google_event_id,
                    google_calendar_id,calendar_provenance,calendar_sync_status,calendar_sync_error)
                   VALUES (?,0,?,?,?,?,?,?,?)""",
                (
                    task_id, start.strftime("%H:%M:%S"), end.strftime("%H:%M:%S"),
                    old["google_event_id"] if old else None,
                    old["google_calendar_id"] if old else None,
                    old["calendar_provenance"] if old else "legacy_unknown",
                    (
                        "pending_update"
                        if old and old["calendar_provenance"] == "coachai_created"
                        else old["calendar_sync_status"] if old else "not_synced"
                    ),
                    old["calendar_sync_error"] if old else None,
                ),
            )
            self._update_task_no_commit(
                task_id, user_id,
                scheduled_start=start.strftime("%H:%M:%S"),
                scheduled_end=end.strftime("%H:%M:%S"),
            )
            self.execute(
                "UPDATE plans SET schedule_revision=schedule_revision+1 WHERE plan_id=? AND user_id=?",
                (task["plan_id"], user_id),
            )

    def move_task_unscheduled(
        self, task_id: int, user_id: str, destination_plan_id: int, order_index: int,
    ) -> None:
        """Move a logical task between owned plans and remove its date-bound segments."""
        with self.transaction():
            task = self.get_task(task_id, user_id)
            destination = self.get_plan_by_id(destination_plan_id, user_id)
            if task is None or destination is None:
                raise OwnershipError("Task or destination plan is not owned by this user.")
            source_plan_id = int(task["plan_id"])
            self.execute("DELETE FROM task_segments WHERE task_id=?", (task_id,))
            self._update_task_no_commit(
                task_id, user_id, plan_id=destination_plan_id, order_index=order_index,
                scheduled_start=None, scheduled_end=None,
            )
            for plan_id in {source_plan_id, destination_plan_id}:
                self.execute(
                    "UPDATE plans SET schedule_revision=schedule_revision+1 WHERE plan_id=? AND user_id=?",
                    (plan_id, user_id),
                )
            destination_after = self.get_plan_by_id(destination_plan_id, user_id)
            self._enqueue_notification_event_no_commit(
                user_id=user_id,
                event_type="TASK_DEFERRED",
                task_id=task_id,
                scheduled_date=str(destination["plan_date"]),
                schedule_revision=int(destination_after["schedule_revision"] or 0),
                payload={
                    "task_title": str(task["title"]),
                    "source_plan_id": source_plan_id,
                    "destination_plan_id": destination_plan_id,
                },
                idempotency_key=(
                    f"task-deferred:{task_id}:{source_plan_id}:{destination_plan_id}:"
                    f"{destination_after['schedule_revision']}"
                ),
                bump_sync_revision=True,
            )
