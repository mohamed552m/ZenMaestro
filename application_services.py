"""Streamlit-independent, principal-bound CoachAI application services.

These classes are the supported boundary for future transports.  Transport
payloads never select the tenant: every read and write derives ownership from
an immutable :class:`AuthenticatedPrincipal`.
"""

from __future__ import annotations

from dataclasses import asdict, is_dataclass
from datetime import date, datetime, time
from enum import Enum
import json
from typing import Any, Callable, Mapping
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from analytics import AnalyticsEngine, AnalyticsFormatter
from backend_errors import (
    BackendError,
    ExternalProviderFailure,
    RateLimited,
    ResourceNotFound,
    SchedulingConflict,
    ValidationError,
    VersionConflict,
)
from database import Database, OwnershipError, StaleScheduleError
from domain_validation import MAX_CATEGORY_NAME_CHARS, validate_task_fields
from future_planner import FuturePlanPreview, FuturePlanningService
from calendar_security import (
    CalendarOperationQueue,
    CalendarSyncStateStore,
    EncryptedGoogleTokenStore,
    OAuthStateStore,
    VersionedTokenCipher,
)
from google_calendar import GoogleCalendarClient, GoogleCalendarError
from identity import AuthenticatedPrincipal
from mobile_repository import MobileRepository, public_task
from notifications import NotificationRepository, NotificationService, NotificationType
from planner import DayPlanOutput
from planner_service import PlannerService
from rate_limiter import DatabaseRateLimiter, RateLimitExceeded
from recommendation_service import RecommendationService
from scheduler import SchedulingPreferences, UserBreak
from timezone_utils import user_today
from scheduler_service import SchedulerService
from task_application import TaskApplicationService


def _jsonable(value: Any) -> Any:
    if is_dataclass(value):
        return _jsonable(asdict(value))
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, (date, datetime, time)):
        return value.isoformat()
    if isinstance(value, Mapping):
        return {str(key): _jsonable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [_jsonable(item) for item in value]
    return value


def public_device(row: Mapping[str, Any]) -> dict:
    """Return device metadata without push tokens or provider internals."""
    keys = (
        "device_id", "platform", "provider", "app_identifier", "app_version",
        "locale", "timezone", "is_primary", "is_enabled",
        "last_acknowledged_schedule_revision", "last_seen_at",
        "token_invalidated_at", "created_at", "updated_at",
    )
    return {key: row.get(key) for key in keys}


class PrincipalService:
    def __init__(self, db: Database, principal: AuthenticatedPrincipal) -> None:
        self.db = db
        self.principal = principal
        self.user_id = principal.user_id
        self.mobile = MobileRepository(db)

    def _idempotent(
        self,
        *,
        operation: str,
        idempotency_key: str,
        request: Any,
        mutation: Callable[[], dict],
    ) -> dict:
        try:
            result, replayed = self.mobile.execute_idempotent(
                user_id=self.user_id,
                operation=operation,
                idempotency_key=idempotency_key,
                request_payload=_jsonable(request),
                mutation=mutation,
            )
        except BackendError:
            raise
        except OwnershipError as exc:
            raise ResourceNotFound("A referenced resource was not found.") from exc
        except StaleScheduleError as exc:
            raise VersionConflict("The schedule changed before the operation was applied.") from exc
        except ValueError as exc:
            raise ValidationError("The request contains an invalid value.") from exc
        result["idempotent_replay"] = replayed
        return result

    def _require_plan(self, plan_id: int) -> dict:
        plan = self.db.get_plan_by_id(int(plan_id), self.user_id)
        if plan is None:
            raise ResourceNotFound("Plan was not found.")
        return plan

    def _require_mutable_plan(self, plan_id: int) -> dict:
        plan = self._require_plan(plan_id)
        plan_date = date.fromisoformat(str(plan["plan_date"])[:10])
        if plan_date < user_today(self.db, self.user_id):
            raise ValidationError("Past plans are read-only.")
        return plan

    def _record_plan_state(self, plan_id: int, *, tasks: bool = True) -> int:
        revision = self.mobile.record_plan_bundle(
            user_id=self.user_id, plan_id=int(plan_id),
        )
        if tasks:
            for row in self.db.get_tasks_by_plan(int(plan_id), self.user_id):
                revision = self.mobile.record_change(
                    user_id=self.user_id,
                    entity_type="task",
                    entity_id=int(row["task_id"]),
                    change_type="upsert",
                    entity_version=int(row.get("version") or 0),
                    payload=public_task(row),
                )
        return revision


class SchedulingApplicationService(PrincipalService):
    """Retry-safe scheduling, manual-move, Future Apply, and Undo commands."""

    def __init__(self, db: Database, principal: AuthenticatedPrincipal) -> None:
        super().__init__(db, principal)
        self.scheduler = SchedulerService(db)
        self.future = FuturePlanningService(db)

    def _queue_calendar_reconciliation(
        self, *, operation_id: str | None, plan_ids: list[int], key: str,
    ) -> str | None:
        if not operation_id:
            return None
        queued, _created = CalendarOperationQueue(self.db).enqueue(
            self.principal,
            operation_type="reconcile_schedule",
            idempotency_key=key,
            payload={
                "schedule_operation_id": operation_id,
                "plan_ids": sorted({int(value) for value in plan_ids}),
            },
        )
        return queued.operation_id

    def schedule_plan(
        self,
        plan_id: int,
        *,
        expected_revision: int,
        work_day_start: time,
        idempotency_key: str,
        breaks: list[tuple[time, int]] | None = None,
        blocked_slots: list[tuple] | None = None,
    ) -> dict:
        self._require_mutable_plan(plan_id)
        user_breaks = [
            UserBreak(start_time=start, duration_minutes=int(minutes))
            for start, minutes in (breaks or [])
        ]
        request = {
            "plan_id": int(plan_id),
            "expected_revision": int(expected_revision),
            "work_day_start": work_day_start,
            "breaks": breaks or [],
            "blocked_slots": blocked_slots or [],
        }

        def mutation() -> dict:
            try:
                scheduled = self.scheduler.schedule_plan(
                    int(plan_id), self.user_id,
                    SchedulingPreferences(
                        work_day_start=work_day_start,
                        user_breaks=user_breaks,
                    ),
                    blocked_slots=blocked_slots,
                    expected_revision=int(expected_revision),
                )
            except StaleScheduleError as exc:
                raise VersionConflict() from exc
            revision = self._record_plan_state(plan_id)
            updated_plan = self._require_plan(plan_id)
            calendar_operation_id = self._queue_calendar_reconciliation(
                operation_id=self.scheduler.last_operation_id,
                plan_ids=[int(plan_id)], key=f"schedule:{self.scheduler.last_operation_id}",
            )
            return {
                "plan_id": int(plan_id),
                "operation_id": self.scheduler.last_operation_id,
                "scheduled_tasks": _jsonable(scheduled),
                "unplaced": _jsonable(self.scheduler.last_unplaced),
                "conflicts": _jsonable(
                    list(self.scheduler.last_conflicts)
                    + list(self.scheduler.last_fixed_conflicts)
                ),
                "sync_revision": revision,
                "schedule_revision": int(updated_plan.get("schedule_revision") or 0),
                "calendar_operation_id": calendar_operation_id,
                "calendar_reconciliation_pending": bool(calendar_operation_id),
            }

        return self._idempotent(
            operation="schedule.apply",
            idempotency_key=idempotency_key,
            request=request,
            mutation=mutation,
        )

    def add_and_schedule_task(
        self,
        plan_id: int,
        *,
        expected_revision: int,
        title: str,
        estimated_minutes: int,
        is_fixed_time: bool,
        work_day_start: time,
        idempotency_key: str,
        fixed_start: time | None = None,
        category_id: int | None = None,
        description: str = "",
        priority: int = 3,
        order_index: int = 0,
        blocked_slots: list[tuple] | None = None,
    ) -> dict:
        self._require_mutable_plan(plan_id)
        clean_title, clean_description, minutes = validate_task_fields(
            title=title, description=description, estimated_minutes=estimated_minutes,
        )
        request = {
            "plan_id": int(plan_id), "expected_revision": int(expected_revision),
            "title": clean_title, "description": clean_description,
            "estimated_minutes": minutes, "is_fixed_time": bool(is_fixed_time),
            "fixed_start": fixed_start, "category_id": category_id,
            "priority": int(priority), "order_index": int(order_index),
            "work_day_start": work_day_start, "blocked_slots": blocked_slots or [],
        }

        def mutation() -> dict:
            try:
                outcome = self.scheduler.add_and_schedule_task(
                    int(plan_id), self.user_id,
                    SchedulingPreferences(work_day_start=work_day_start),
                    title=clean_title, estimated_minutes=minutes,
                    is_fixed_time=bool(is_fixed_time), fixed_start=fixed_start,
                    category_id=category_id, description=clean_description,
                    priority=int(priority), order_index=int(order_index),
                    blocked_slots=blocked_slots,
                    expected_revision=int(expected_revision),
                )
            except StaleScheduleError as exc:
                raise VersionConflict() from exc
            revision = self._record_plan_state(plan_id)
            updated_plan = self._require_plan(plan_id)
            result = _jsonable(outcome)
            result["sync_revision"] = revision
            result["schedule_revision"] = int(updated_plan.get("schedule_revision") or 0)
            result["calendar_operation_id"] = self._queue_calendar_reconciliation(
                operation_id=outcome.operation_id, plan_ids=[int(plan_id)],
                key=f"schedule:{outcome.operation_id}",
            )
            result["calendar_reconciliation_pending"] = bool(
                result["calendar_operation_id"]
            )
            return result

        return self._idempotent(
            operation="schedule.add_task",
            idempotency_key=idempotency_key,
            request=request,
            mutation=mutation,
        )

    def move_task(
        self,
        task_id: int,
        *,
        expected_schedule_revision: int,
        new_start: time,
        idempotency_key: str,
        blocked_slots: list[tuple] | None = None,
    ) -> dict:
        task = self.db.get_task(int(task_id), self.user_id)
        if task is None:
            raise ResourceNotFound("Task was not found.")
        plan_id = int(task["plan_id"])
        self._require_mutable_plan(plan_id)
        request = {
            "task_id": int(task_id),
            "expected_schedule_revision": int(expected_schedule_revision),
            "new_start": new_start,
            "blocked_slots": blocked_slots or [],
        }

        def mutation() -> dict:
            try:
                outcome = self.scheduler.move_flexible_task(
                    int(task_id), self.user_id, new_start,
                    int(expected_schedule_revision), blocked_slots,
                )
            except StaleScheduleError as exc:
                raise VersionConflict() from exc
            revision = self._record_plan_state(plan_id)
            updated_plan = self._require_plan(plan_id)
            result = _jsonable(outcome)
            result["sync_revision"] = revision
            result["schedule_revision"] = int(updated_plan.get("schedule_revision") or 0)
            result["calendar_operation_id"] = self._queue_calendar_reconciliation(
                operation_id=outcome.operation_id, plan_ids=[plan_id],
                key=f"schedule:{outcome.operation_id}",
            )
            result["calendar_reconciliation_pending"] = bool(
                result["calendar_operation_id"]
            )
            return result

        return self._idempotent(
            operation="schedule.manual_move",
            idempotency_key=idempotency_key,
            request=request,
            mutation=mutation,
        )

    def preview_future(self, request: Any) -> FuturePlanPreview:
        """Pure deterministic preview; no transaction and no persistence."""
        return self.future.preview_request(self.user_id, request)

    def future_candidates(self, planning_start: date, planning_end: date) -> dict:
        """Return only safe, editable tasks owned by the authenticated principal."""
        try:
            self.future.validate_horizon(planning_start, planning_end)
            from timezone_utils import user_today

            if planning_start < user_today(self.db, self.user_id):
                raise ValueError("Future Planning cannot include dates before today.")
        except (TypeError, ValueError) as exc:
            raise ValidationError(str(exc)) from exc
        rows = self.db.get_future_planning_tasks(
            self.user_id, planning_start, planning_end,
        )
        tasks = []
        for row in rows:
            if (
                bool(row.get("is_fixed_time"))
                or bool(row.get("is_break"))
                or str(row.get("status")) in {"completed", "failed"}
                or bool(row.get("started_at"))
            ):
                continue
            estimated = int(row.get("estimated_minutes") or 0)
            actual = int(row.get("actual_minutes") or 0)
            tasks.append({
                "task_id": int(row["task_id"]),
                "plan_date": str(row["plan_date"]),
                "title": str(row["title"]),
                "description": str(row.get("description") or ""),
                "remaining_minutes": max(0, estimated - actual),
                "priority": int(row.get("priority") or 3),
                "earliest_start": (
                    str(row.get("earliest_start")) if row.get("earliest_start") else None
                ),
                "deadline": str(row.get("deadline")) if row.get("deadline") else None,
            })
        return {
            "planning_start": planning_start.isoformat(),
            "planning_end": planning_end.isoformat(),
            "tasks": tasks,
        }

    def public_future_preview(self, request: Any) -> dict:
        try:
            preview = self.preview_future(request)
        except (TypeError, ValueError) as exc:
            raise ValidationError(str(exc)) from exc
        return {
            "planning_start": preview.planning_start.isoformat(),
            "planning_end": preview.planning_end.isoformat(),
            "work_day_start": preview.work_day_start.isoformat(timespec="minutes"),
            "outcomes": _jsonable(preview.outcomes),
            "changes": _jsonable(preview.changes),
            "placed_count": sum(1 for item in preview.outcomes if item.placed),
            "unplaced_count": sum(1 for item in preview.outcomes if not item.placed),
        }

    def preview_and_apply_future(
        self, request: Any, *, idempotency_key: str,
    ) -> dict:
        """Apply one structured request idempotently without re-previewing on replay."""
        request_fingerprint = (
            request.model_dump(mode="json")
            if hasattr(request, "model_dump")
            else _jsonable(request)
        )

        def mutation() -> dict:
            try:
                preview = self.preview_future(request)
            except (TypeError, ValueError) as exc:
                raise ValidationError(str(exc)) from exc
            result = self._apply_future_preview(preview)
            result["preview"] = {
                "placed_count": sum(1 for item in preview.outcomes if item.placed),
                "unplaced_count": sum(1 for item in preview.outcomes if not item.placed),
            }
            return result

        return self._idempotent(
            operation="future_plan.request.apply",
            idempotency_key=idempotency_key,
            request=request_fingerprint,
            mutation=mutation,
        )

    @staticmethod
    def _future_fingerprint(preview: FuturePlanPreview) -> dict:
        return {
            "planning_start": preview.planning_start,
            "planning_end": preview.planning_end,
            "work_day_start": preview.work_day_start,
            "expected_revisions": preview.expected_revisions,
            "expected_task_states": preview.expected_task_states,
            "segments_by_task": preview.segments_by_task,
            "task_updates": preview.task_updates,
            "draft_tasks": preview.draft_tasks,
            "draft_segments": preview.draft_segments,
            "expected_anchor_plan_id": preview.expected_anchor_plan_id,
        }

    def apply_future(self, preview: FuturePlanPreview, *, idempotency_key: str) -> dict:
        if preview.user_id != self.user_id:
            raise ResourceNotFound("Future-planning preview was not found.")
        request = self._future_fingerprint(preview)

        return self._idempotent(
            operation="future_plan.apply",
            idempotency_key=idempotency_key,
            request=request,
            mutation=lambda: self._apply_future_preview(preview),
        )

    def _apply_future_preview(self, preview: FuturePlanPreview) -> dict:
        if preview.user_id != self.user_id:
            raise ResourceNotFound("Future-planning preview was not found.")
        try:
            operation_id = self.future.apply(preview)
        except StaleScheduleError as exc:
            raise VersionConflict() from exc
        plans = self.db.get_plans_in_range(
            self.user_id, preview.planning_start, preview.planning_end,
        )
        revision = self.mobile.current_revision(self.user_id)
        for plan in plans:
            revision = self._record_plan_state(int(plan["plan_id"]))
        plan_ids = [int(plan["plan_id"]) for plan in plans]
        calendar_operation_id = self._queue_calendar_reconciliation(
            operation_id=operation_id, plan_ids=plan_ids,
            key=f"future-plan:{operation_id}",
        )
        return {
            "operation_id": operation_id,
            "plan_ids": plan_ids,
            "sync_revision": revision,
            "calendar_operation_id": calendar_operation_id,
            "calendar_reconciliation_pending": bool(calendar_operation_id),
        }

    def undo(self, operation_id: str, *, idempotency_key: str) -> dict:
        operation = self.db.fetch_one(
            """SELECT so.* FROM schedule_operations so
               JOIN plans p ON p.plan_id=so.plan_id
               WHERE so.operation_id=? AND so.user_id=? AND p.user_id=?""",
            (str(operation_id), self.user_id, self.user_id),
        )
        if operation is None:
            raise ResourceNotFound("Schedule operation was not found.")
        request = {"operation_id": str(operation_id)}

        def mutation() -> dict:
            self.db.undo_schedule_operation(str(operation_id), self.user_id)
            plan_ids = {int(operation["plan_id"])}
            for field in ("before_state", "after_state"):
                state = json.loads(str(operation.get(field) or "{}"))
                plan_ids.update(int(key) for key in state.get("plan_revisions", {}))
            revision = self.mobile.current_revision(self.user_id)
            for plan_id in sorted(plan_ids):
                if self.db.get_plan_by_id(plan_id, self.user_id) is not None:
                    revision = self._record_plan_state(plan_id)
            calendar_operation, _created = CalendarOperationQueue(self.db).enqueue(
                self.principal,
                operation_type="reconcile_schedule_undo",
                idempotency_key=idempotency_key,
                payload={
                    "schedule_operation_id": str(operation_id),
                    "plan_ids": sorted(plan_ids),
                },
            )
            return {
                "operation_id": str(operation_id), "undone": True,
                "calendar_reconciliation_pending": True,
                "calendar_operation_id": calendar_operation.operation_id,
                "sync_revision": revision,
                "schedule_revisions": {
                    str(plan_id): int(
                        self._require_plan(plan_id).get("schedule_revision") or 0
                    )
                    for plan_id in sorted(plan_ids)
                    if self.db.get_plan_by_id(plan_id, self.user_id) is not None
                },
            }

        return self._idempotent(
            operation="schedule.undo",
            idempotency_key=idempotency_key,
            request=request,
            mutation=mutation,
        )


class NotificationApplicationService(PrincipalService):
    def __init__(self, db: Database, principal: AuthenticatedPrincipal) -> None:
        super().__init__(db, principal)
        self.repository = NotificationRepository(db)
        self.service = NotificationService(db)

    def _reconcile_task_reminder_preferences(self, *, reason: str) -> dict:
        cancelled = self.repository.cancel_local_schedules(
            self.user_id,
            notification_type=NotificationType.TASK_REMINDER,
            reason=reason,
        )
        self.db.execute(
            """UPDATE notification_outbox
               SET status='cancelled',processed_at=CURRENT_TIMESTAMP,last_error=?,
                   locked_at=NULL,locked_by=NULL
               WHERE user_id=? AND event_type='TASK_REMINDER_DUE'
                 AND status IN ('queued','failed')""",
            (reason, self.user_id),
        )
        task_rows = self.db.fetch_all(
            """SELECT DISTINCT t.task_id
               FROM tasks t JOIN plans p ON p.plan_id=t.plan_id
               JOIN task_segments s ON s.task_id=t.task_id
               WHERE p.user_id=? AND t.status='pending'""",
            (self.user_id,),
        )
        reconciled = self.service.reconcile_schedule_notifications(
            self.user_id,
            [int(row["task_id"]) for row in task_rows],
            operation_id=f"preferences:{reason}",
        )
        return {
            "local_schedules_cancelled": cancelled,
            "notification_reminders_queued": int(
                reconciled.get("scheduled") or 0
            ),
        }

    def _device(self, device_id: str) -> dict:
        try:
            return self.repository.get_device(self.user_id, device_id)
        except OwnershipError as exc:
            raise ResourceNotFound("Notification device was not found.") from exc

    def _notification(self, notification_id: str) -> dict:
        try:
            return self.repository.get_notification(
                self.user_id, notification_id
            )
        except OwnershipError as exc:
            raise ResourceNotFound("Notification was not found.") from exc

    def register_device(
        self,
        *,
        device_id: str,
        platform: str,
        provider: str,
        idempotency_key: str,
        push_token: str | None = None,
        app_identifier: str | None = None,
        app_version: str | None = None,
        locale: str = "en",
        timezone_name: str = "UTC",
        is_primary: bool = False,
        is_enabled: bool = True,
    ) -> dict:
        request = {
            "device_id": device_id, "platform": platform, "provider": provider,
            # Hashing happens inside the idempotency repository; the token is
            # never copied to the response or logs by this layer.
            "push_token": push_token, "app_identifier": app_identifier,
            "app_version": app_version, "locale": locale,
            "timezone_name": timezone_name, "is_primary": is_primary,
            "is_enabled": is_enabled,
        }

        def mutation() -> dict:
            row = self.repository.register_device(
                self.user_id, device_id=device_id, platform=platform,
                provider=provider, push_token=push_token,
                app_identifier=app_identifier, app_version=app_version,
                locale=locale, timezone_name=timezone_name,
                is_primary=is_primary, is_enabled=is_enabled,
            )
            return public_device(row)

        return self._idempotent(
            operation="notification.device.register",
            idempotency_key=idempotency_key,
            request=request,
            mutation=mutation,
        )

    def list_devices(self) -> list[dict]:
        return [public_device(row) for row in self.repository.list_devices(self.user_id)]

    def invalidate_device(self, device_id: str, *, idempotency_key: str) -> dict:
        self._device(device_id)

        def mutation() -> dict:
            self.repository.invalidate_device_token(self.user_id, device_id)
            return {"device_id": device_id, "invalidated": True}

        return self._idempotent(
            operation="notification.device.invalidate",
            idempotency_key=idempotency_key,
            request={"device_id": device_id},
            mutation=mutation,
        )

    def update_preferences(self, *, idempotency_key: str, changes: dict[str, Any]) -> dict:
        def mutation() -> dict:
            row = self.repository.update_preferences(self.user_id, **changes)
            reconciliation = {}
            if set(changes) & {
                "tone", "default_reminder_lead_minutes", "quiet_hours_start",
                "quiet_hours_end", "privacy_mode", "sound_enabled",
                "vibration_enabled", "multi_device_policy", "locale", "time_format",
            }:
                reconciliation = self._reconcile_task_reminder_preferences(
                    reason="notification_preferences_changed"
                )
            revision = self.mobile.record_change(
                user_id=self.user_id, entity_type="notification_preferences",
                entity_id=self.user_id, change_type="upsert", entity_version=None,
                payload=dict(row),
            )
            result = dict(row)
            result.update(reconciliation)
            result["sync_revision"] = revision
            return result

        return self._idempotent(
            operation="notification.preferences.update",
            idempotency_key=idempotency_key,
            request=changes,
            mutation=mutation,
        )

    def get_preferences(self) -> dict:
        return dict(self.repository.get_preferences(self.user_id))

    def get_type_preference(self, notification_type: str) -> dict | None:
        row = self.repository.get_type_preference(self.user_id, notification_type)
        return dict(row) if row is not None else None

    def set_type_preference(
        self,
        notification_type: str,
        *,
        enabled: bool,
        preferred_channel: str,
        lead_minutes: int | None,
        cooldown_seconds: int,
        interruption_level: str,
        idempotency_key: str,
    ) -> dict:
        request = {
            "notification_type": notification_type,
            "enabled": bool(enabled),
            "preferred_channel": preferred_channel,
            "lead_minutes": lead_minutes,
            "cooldown_seconds": int(cooldown_seconds),
            "interruption_level": interruption_level,
        }

        def mutation() -> dict:
            row = self.repository.set_type_preference(
                self.user_id,
                notification_type,
                enabled=bool(enabled),
                preferred_channel=preferred_channel,
                lead_minutes=lead_minutes,
                cooldown_seconds=int(cooldown_seconds),
                interruption_level=interruption_level,
            )
            kind = str(row["notification_type"])
            if kind == NotificationType.TASK_REMINDER.value:
                reconciliation = self._reconcile_task_reminder_preferences(
                    reason="notification_type_preference_changed"
                )
            else:
                reconciliation = {
                    "local_schedules_cancelled": self.repository.cancel_local_schedules(
                        self.user_id,
                        notification_type=kind,
                        reason="notification_type_preference_changed",
                    ),
                    "notification_reminders_queued": 0,
                }
            revision = self.mobile.record_change(
                user_id=self.user_id,
                entity_type="notification_type_preference",
                entity_id=str(notification_type),
                change_type="upsert",
                entity_version=None,
                payload=dict(row),
            )
            result = dict(row)
            result.update(reconciliation)
            result["sync_revision"] = revision
            return result

        return self._idempotent(
            operation="notification.type_preference.update",
            idempotency_key=idempotency_key,
            request=request,
            mutation=mutation,
        )

    def acknowledge_schedule_revision(
        self,
        device_id: str,
        sync_revision: int,
        *,
        idempotency_key: str,
    ) -> dict:
        self._device(device_id)

        def mutation() -> dict:
            self.repository.acknowledge_schedule_revision(
                self.user_id, device_id, int(sync_revision)
            )
            device = self.repository.get_device(self.user_id, device_id)
            return {
                "device_id": device_id,
                "acknowledged_schedule_revision": int(
                    device.get("last_acknowledged_schedule_revision") or 0
                ),
            }

        return self._idempotent(
            operation="notification.schedule_revision.acknowledge",
            idempotency_key=idempotency_key,
            request={
                "device_id": device_id,
                "sync_revision": int(sync_revision),
            },
            mutation=mutation,
        )

    def mark_read(self, notification_id: str, *, idempotency_key: str) -> dict:
        self._notification(notification_id)

        def mutation() -> dict:
            self.repository.mark_read(self.user_id, notification_id)
            row = self.repository.get_notification(self.user_id, notification_id)
            return {"notification_id": notification_id, "read_at": row.get("read_at")}

        return self._idempotent(
            operation="notification.read",
            idempotency_key=idempotency_key,
            request={"notification_id": notification_id}, mutation=mutation,
        )

    def mark_acted(self, notification_id: str, *, idempotency_key: str) -> dict:
        row = self._notification(notification_id)
        if str(row.get("status")) != "active":
            raise ValidationError("A stale or cancelled notification cannot be acted on.")

        def mutation() -> dict:
            self.repository.mark_acted(self.user_id, notification_id)
            updated = self.repository.get_notification(self.user_id, notification_id)
            return {
                "notification_id": notification_id,
                "acted_at": updated.get("acted_at"),
            }

        return self._idempotent(
            operation="notification.acted",
            idempotency_key=idempotency_key,
            request={"notification_id": notification_id}, mutation=mutation,
        )

    def sync_since(self, revision: int, *, limit: int = 500) -> dict:
        if not 1 <= int(limit) <= 500:
            raise ValidationError("Notification sync limit must be between 1 and 500.")
        return self.repository.sync_since(
            self.user_id, int(revision), limit=int(limit)
        )

    def local_schedules(self, *, device_id: str | None = None) -> list[dict]:
        if device_id is not None:
            self._device(device_id)
        rows = self.repository.list_local_schedules(self.user_id, status="scheduled")
        return [
            {key: row.get(key) for key in (
                "local_schedule_id", "notification_id", "device_id", "task_id",
                "segment_id", "segment_index", "schedule_revision",
                "schedule_operation_id", "trigger_type", "scheduled_fire_at",
                "expires_at", "status", "payload", "locale", "timezone",
                "updated_at",
            )}
            for row in rows
            if device_id is None or row.get("device_id") in {None, device_id}
        ]


class CalendarApplicationService(PrincipalService):
    """Secure OAuth and durable Calendar intent boundary.

    Provider calls are limited to OAuth code exchange, outside a database
    transaction. Sync and event mutations are queued for a separate worker.
    """

    @staticmethod
    def _oauth_config() -> tuple[str, str, str]:
        import os

        client_id = (os.getenv("GOOGLE_CLIENT_ID") or "").strip()
        client_secret = (os.getenv("GOOGLE_CLIENT_SECRET") or "").strip()
        redirect_uri = (
            os.getenv("GOOGLE_REDIRECT_URI") or "http://localhost:8501"
        ).strip()
        if not client_id or not client_secret:
            from backend_errors import ConfigurationError

            raise ConfigurationError("Google Calendar OAuth is not configured.")
        return client_id, client_secret, redirect_uri

    def _tokens(self) -> EncryptedGoogleTokenStore:
        return EncryptedGoogleTokenStore(self.db, VersionedTokenCipher.from_env())

    @staticmethod
    def _public_operation(operation) -> dict:
        return {
            "operation_id": operation.operation_id,
            "operation_type": operation.operation_type,
            "payload": _jsonable(operation.payload),
            "status": operation.status,
            "attempts": operation.attempts,
            "available_at": _jsonable(operation.available_at),
            "last_error": operation.last_error,
            "created_at": _jsonable(operation.created_at),
            "completed_at": _jsonable(operation.completed_at),
        }

    def begin_authorization(self) -> dict:
        client_id, _client_secret, redirect_uri = self._oauth_config()
        issued = OAuthStateStore(self.db).issue(
            self.principal, redirect_uri=redirect_uri,
        )
        return {
            "authorization_url": GoogleCalendarClient.build_auth_url(
                client_id, redirect_uri,
                state=issued.state,
                code_challenge=issued.code_challenge,
            ),
            "expires_at": issued.expires_at.isoformat(),
        }

    def complete_authorization(self, *, code: str, state: str) -> dict:
        client_id, client_secret, redirect_uri = self._oauth_config()
        consumed = OAuthStateStore(self.db).consume(
            self.principal, state=state, redirect_uri=redirect_uri,
        )
        # The external exchange is intentionally between two short DB
        # transactions: state consumption and encrypted credential storage.
        try:
            token_data = GoogleCalendarClient.exchange_code(
                code, client_id, client_secret, redirect_uri,
                code_verifier=consumed.code_verifier,
            )
        except GoogleCalendarError as exc:
            from backend_errors import ExternalProviderFailure

            raise ExternalProviderFailure(
                "Google Calendar authorization could not be completed."
            ) from exc
        expiry = token_data.get("token_expiry")
        if expiry is None:
            from backend_errors import ExternalProviderFailure

            raise ExternalProviderFailure(
                "Google Calendar authorization returned an invalid response."
            )
        with self.db.transaction():
            self._tokens().save(
                self.principal,
                access_token=str(token_data["access_token"]),
                refresh_token=token_data.get("refresh_token"),
                token_expiry=expiry,
                scopes="calendar.readonly calendar.events",
            )
            revision = self.mobile.record_change(
                user_id=self.user_id, entity_type="calendar_connection",
                entity_id="google", change_type="upsert", entity_version=None,
                payload={"provider": "google", "connected": True},
            )
        return {"provider": "google", "connected": True, "sync_revision": revision}

    def connection_status(self) -> dict:
        try:
            connected = self._tokens().get(self.principal) is not None
        except BackendError:
            connected = False
        return {"provider": "google", "connected": connected}

    def overview(self) -> dict:
        status = self.connection_status()
        selected = [
            {
                "calendar_id": str(row["calendar_id"]),
                "name": str(row.get("calendar_name") or ""),
                "color": str(row.get("color") or "#4285F4"),
                "is_primary": bool(row.get("is_primary")),
                "selected": True,
            }
            for row in self.db.get_selected_calendars(self.user_id)
        ]
        return {**status, "selected_calendars": selected}

    def list_available_calendars(self) -> list[dict]:
        credentials = self._tokens().get(self.principal)
        if credentials is None:
            from backend_errors import AuthenticationRequired

            raise AuthenticationRequired("Google Calendar is not connected.")
        client_id, client_secret, _redirect_uri = self._oauth_config()
        client = GoogleCalendarClient(
            credentials.access_token,
            credentials.refresh_token,
            credentials.token_expiry,
            client_id=client_id,
            client_secret=client_secret,
        )
        try:
            refreshed = client.refresh_if_expired()
            if refreshed:
                self._tokens().save(
                    self.principal,
                    access_token=str(refreshed["access_token"]),
                    refresh_token=None,
                    token_expiry=refreshed["token_expiry"],
                    scopes=credentials.scopes,
                )
            calendars = client.list_calendars()
        except GoogleCalendarError as exc:
            raise ExternalProviderFailure(
                "Google Calendar list could not be loaded."
            ) from exc
        selected_ids = set(self.db.get_selected_calendar_ids(self.user_id))
        return [
            {
                "calendar_id": str(item.get("calendar_id") or ""),
                "name": str(item.get("name") or "")[:300],
                "color": str(item.get("color") or "#4285F4")[:32],
                "is_primary": bool(item.get("is_primary")),
                "selected": str(item.get("calendar_id") or "") in selected_ids,
            }
            for item in calendars
            if str(item.get("calendar_id") or "").strip()
        ]

    def disconnect(self, *, idempotency_key: str) -> dict:
        def mutation() -> dict:
            self.db.delete_all_google_calendar_events(self.user_id)
            self.db.delete_selected_calendars(self.user_id)
            self.db.delete_google_tokens(self.user_id)
            self.db.execute(
                "UPDATE calendar_operations SET status='cancelled' "
                "WHERE user_id=? AND status IN ('queued','failed')",
                (self.user_id,),
            )
            revision = self.mobile.record_change(
                user_id=self.user_id, entity_type="calendar_connection",
                entity_id="google", change_type="upsert", entity_version=None,
                payload={"provider": "google", "connected": False},
            )
            return {"provider": "google", "connected": False, "sync_revision": revision}

        return self._idempotent(
            operation="calendar.disconnect", idempotency_key=idempotency_key,
            request={"provider": "google"}, mutation=mutation,
        )

    def set_selected_calendars(
        self, selections: list[dict], *, idempotency_key: str,
    ) -> dict:
        normalized = []
        seen: set[str] = set()
        for selection in selections:
            calendar_id = str(selection.get("calendar_id") or "").strip()
            if not calendar_id or len(calendar_id) > 1024 or calendar_id in seen:
                raise ValidationError("Calendar selection is invalid.")
            seen.add(calendar_id)
            normalized.append({
                "calendar_id": calendar_id,
                "calendar_name": str(selection.get("calendar_name") or "")[:300],
                "color": str(selection.get("color") or "#4285F4")[:32],
                "is_primary": bool(selection.get("is_primary")),
            })

        def mutation() -> dict:
            self.db.save_selected_calendars(self.user_id, normalized)
            revision = self.mobile.record_change(
                user_id=self.user_id, entity_type="calendar_selection",
                entity_id="google", change_type="upsert", entity_version=None,
                payload={"calendars": normalized},
            )
            return {"calendars": normalized, "sync_revision": revision}

        return self._idempotent(
            operation="calendar.selection.update", idempotency_key=idempotency_key,
            request={"calendars": normalized}, mutation=mutation,
        )

    def request_sync(
        self,
        *,
        range_start: date,
        range_end: date,
        idempotency_key: str,
    ) -> dict:
        if range_end < range_start or (range_end - range_start).days + 1 > 84:
            raise ValidationError("Calendar sync range must be between 1 and 84 days.")
        calendars = self.db.get_selected_calendar_ids(self.user_id)
        if not calendars:
            raise ValidationError("At least one Calendar must be selected before sync.")

        def mutation() -> dict:
            states = CalendarSyncStateStore(self.db, max_range_days=84)
            queue = CalendarOperationQueue(self.db)
            operation_ids = []
            for calendar_id in calendars:
                states.mark_pending(
                    self.principal, calendar_id=str(calendar_id),
                    range_start=range_start, range_end=range_end,
                )
                operation, _created = queue.enqueue(
                    self.principal,
                    operation_type="sync.range",
                    idempotency_key=f"{idempotency_key}:{calendar_id}",
                    payload={
                        "calendar_id": str(calendar_id),
                        "range_start": range_start.isoformat(),
                        "range_end": range_end.isoformat(),
                    },
                )
                operation_ids.append(operation.operation_id)
            revision = self.mobile.record_change(
                user_id=self.user_id, entity_type="calendar_sync_request",
                entity_id=idempotency_key, change_type="upsert", entity_version=None,
                payload={
                    "range_start": range_start.isoformat(),
                    "range_end": range_end.isoformat(),
                    "status": "pending", "operation_ids": operation_ids,
                },
            )
            return {
                "status": "pending", "operation_ids": operation_ids,
                "range_start": range_start.isoformat(),
                "range_end": range_end.isoformat(),
                "sync_revision": revision,
            }

        return self._idempotent(
            operation="calendar.sync.request", idempotency_key=idempotency_key,
            request={"range_start": range_start, "range_end": range_end,
                     "calendars": calendars}, mutation=mutation,
        )

    def queue_mutation(
        self,
        *,
        operation_type: str,
        payload: dict[str, Any],
        idempotency_key: str,
    ) -> dict:
        if operation_type not in {"event.upsert", "event.delete"}:
            raise ValidationError("Unsupported client Calendar mutation.")
        if not isinstance(payload, Mapping):
            raise ValidationError("Calendar mutation payload must be an object.")
        task_id = payload.get("task_id")
        if operation_type == "event.upsert":
            try:
                resolved_task_id = int(task_id)
            except (TypeError, ValueError) as exc:
                raise ResourceNotFound("Task was not found.") from exc
            if self.db.get_task(resolved_task_id, self.user_id) is None:
                raise ResourceNotFound("Task was not found.")
            requested_segment_ids = payload.get("segment_ids")
            normalized_segment_ids: list[int] | None = None
            if requested_segment_ids is not None:
                if (
                    not isinstance(requested_segment_ids, (list, tuple, set))
                    or not 1 <= len(requested_segment_ids) <= 500
                ):
                    raise ValidationError("Calendar segment selection is invalid.")
                try:
                    normalized_segment_ids = sorted(
                        {int(value) for value in requested_segment_ids}
                    )
                except (TypeError, ValueError) as exc:
                    raise ValidationError(
                        "Calendar segment selection is invalid."
                    ) from exc
                owned_ids = {
                    int(row["segment_id"])
                    for row in self.db.get_task_segments(
                        resolved_task_id, self.user_id
                    )
                }
                if not normalized_segment_ids or not set(
                    normalized_segment_ids
                ).issubset(owned_ids):
                    raise ResourceNotFound("Calendar segment was not found.")
            normalized_payload: dict[str, Any] = {"task_id": resolved_task_id}
            if normalized_segment_ids is not None:
                normalized_payload["segment_ids"] = normalized_segment_ids
        else:
            event_id = str(payload.get("event_id") or "").strip()
            calendar_id = str(payload.get("calendar_id") or "primary").strip()
            if len(event_id) > 1024 or not calendar_id or len(calendar_id) > 1024:
                raise ValidationError("Calendar event identity is invalid.")
            owned = self.db.fetch_one(
                """SELECT s.segment_id FROM task_segments s
                   JOIN tasks t ON t.task_id=s.task_id
                   JOIN plans p ON p.plan_id=t.plan_id
                   WHERE p.user_id=? AND s.google_event_id=?
                     AND COALESCE(s.google_calendar_id,'primary')=?
                     AND s.calendar_provenance='coachai_created'""",
                (self.user_id, event_id, calendar_id),
            )
            if not event_id or owned is None:
                raise ResourceNotFound("Calendar event was not found.")
            normalized_payload = {
                "event_id": event_id,
                "calendar_id": calendar_id,
            }
        operation, created = CalendarOperationQueue(self.db).enqueue(
            self.principal, operation_type=operation_type,
            idempotency_key=idempotency_key, payload=normalized_payload,
        )
        return {
            "operation_id": operation.operation_id,
            "status": operation.status,
            "created": created,
        }

    def get_operation(self, operation_id: str) -> dict:
        operation = CalendarOperationQueue(self.db).get(
            self.principal, str(operation_id)
        )
        return self._public_operation(operation)

    def list_operations(
        self,
        *,
        statuses: list[str] | tuple[str, ...] | None = None,
        limit: int = 100,
    ) -> list[dict]:
        operations = CalendarOperationQueue(self.db).list_for_user(
            self.principal,
            statuses=statuses,
            limit=int(limit),
        )
        return [self._public_operation(operation) for operation in operations]

    def cancel_operation(
        self,
        operation_id: str,
        *,
        idempotency_key: str,
    ) -> dict:
        CalendarOperationQueue(self.db).get(self.principal, str(operation_id))

        def mutation() -> dict:
            operation = CalendarOperationQueue(self.db).cancel(
                self.principal, str(operation_id)
            )
            return self._public_operation(operation)

        return self._idempotent(
            operation="calendar.operation.cancel",
            idempotency_key=idempotency_key,
            request={"operation_id": str(operation_id)},
            mutation=mutation,
        )


class AccountApplicationService(PrincipalService):
    def get_profile(self) -> dict:
        row = self.db.fetch_one(
            """SELECT user_id,email,display_name,timezone,is_disabled,created_at,updated_at
               FROM users WHERE user_id=?""",
            (self.user_id,),
        )
        if row is None:
            raise ResourceNotFound("User was not found.")
        return row

    def create_category(
        self, name: str, *, color: str, idempotency_key: str,
    ) -> dict:
        cleaned = str(name or "").strip()
        if not cleaned or len(cleaned) > MAX_CATEGORY_NAME_CHARS:
            raise ValidationError(
                f"Category name must be between 1 and {MAX_CATEGORY_NAME_CHARS} characters."
            )

        def mutation() -> dict:
            category_id = self.db.create_category(self.user_id, cleaned, color)
            row = self.db.fetch_one(
                "SELECT * FROM categories WHERE category_id=? AND user_id=?",
                (category_id, self.user_id),
            )
            revision = self.mobile.record_change(
                user_id=self.user_id, entity_type="category",
                entity_id=category_id, change_type="upsert",
                entity_version=None, payload=row,
            )
            result = dict(row)
            result["sync_revision"] = revision
            return result

        return self._idempotent(
            operation="category.create", idempotency_key=idempotency_key,
            request={"name": cleaned, "color": color}, mutation=mutation,
        )

    def change_display_name(
        self, display_name: str, *, idempotency_key: str,
    ) -> dict:
        cleaned = str(display_name or "").strip()
        if not cleaned or len(cleaned) > 100:
            raise ValidationError(
                "Display name must be between 1 and 100 characters."
            )

        def mutation() -> dict:
            self.db.execute(
                """UPDATE users
                   SET display_name=?,updated_at=CURRENT_TIMESTAMP
                   WHERE user_id=?""",
                (cleaned, self.user_id),
            )
            revision = self.mobile.record_change(
                user_id=self.user_id,
                entity_type="account_settings",
                entity_id=self.user_id,
                change_type="upsert",
                entity_version=None,
                payload={"display_name": cleaned},
            )
            result = dict(self.get_profile())
            result["sync_revision"] = revision
            return result

        return self._idempotent(
            operation="account.display_name.change",
            idempotency_key=idempotency_key,
            request={"display_name": cleaned},
            mutation=mutation,
        )

    def change_timezone(self, timezone_name: str, *, idempotency_key: str) -> dict:
        try:
            ZoneInfo(str(timezone_name))
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise ValidationError("An IANA timezone name is required.") from exc

        def mutation() -> dict:
            old = self.get_profile()["timezone"]
            self.db.execute(
                "UPDATE users SET timezone=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                (timezone_name, self.user_id),
            )
            self.db.execute(
                "UPDATE notification_devices SET timezone=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                (timezone_name, self.user_id),
            )
            notifications = NotificationRepository(self.db)
            cancelled = notifications.cancel_local_schedules(
                self.user_id, reason="timezone_changed",
            )
            self.db.execute(
                """UPDATE notification_outbox
                   SET status='cancelled',processed_at=CURRENT_TIMESTAMP,
                       last_error='timezone_changed',locked_at=NULL,locked_by=NULL
                   WHERE user_id=? AND event_type='TASK_REMINDER_DUE'
                     AND status IN ('queued','failed')""",
                (self.user_id,),
            )
            task_rows = self.db.fetch_all(
                """SELECT DISTINCT t.task_id
                   FROM tasks t
                   JOIN plans p ON p.plan_id=t.plan_id
                   JOIN task_segments s ON s.task_id=t.task_id
                   WHERE p.user_id=? AND t.status='pending'""",
                (self.user_id,),
            )
            notification_reconciliation = NotificationService(
                self.db
            ).reconcile_schedule_notifications(
                self.user_id,
                [int(row["task_id"]) for row in task_rows],
                operation_id=f"timezone:{idempotency_key}",
            )
            calendar_operation, _created = CalendarOperationQueue(self.db).enqueue(
                self.principal,
                operation_type="timezone_reconcile",
                idempotency_key=idempotency_key,
                payload={"old_timezone": old, "new_timezone": timezone_name},
            )
            revision = self.mobile.record_change(
                user_id=self.user_id, entity_type="account_settings",
                entity_id=self.user_id, change_type="upsert", entity_version=None,
                payload={"timezone": timezone_name},
            )
            return {
                "timezone": timezone_name,
                "local_schedules_cancelled": cancelled,
                "notification_reminders_queued": int(
                    notification_reconciliation.get("scheduled") or 0
                ),
                "calendar_reconciliation_pending": True,
                "calendar_operation_id": calendar_operation.operation_id,
                "sync_revision": revision,
            }

        return self._idempotent(
            operation="account.timezone.change",
            idempotency_key=idempotency_key,
            request={"timezone": timezone_name}, mutation=mutation,
        )


class AIPlanningApplicationService(PrincipalService):
    """Strict preview/apply boundary for model-produced day plans."""

    def __init__(self, db: Database, principal: AuthenticatedPrincipal) -> None:
        super().__init__(db, principal)
        self.planner = PlannerService(db)

    def draft(
        self,
        raw_input: str,
        *,
        calendar_events: list[dict] | None = None,
    ) -> dict:
        # The provider call is intentionally outside an application
        # transaction.  This method has no persistence side effects other than
        # the short shared rate-limit counter transaction.
        try:
            output = self.planner.draft_plan(
                raw_input=raw_input,
                user_id=self.user_id,
                calendar_events=calendar_events,
            )
        except RateLimitExceeded as exc:
            raise RateLimited(retry_after_seconds=exc.retry_after_seconds) from exc
        return output.model_dump(mode="json")

    def apply_draft(
        self,
        draft: DayPlanOutput | Mapping[str, Any],
        *,
        raw_input: str,
        idempotency_key: str,
    ) -> dict:
        try:
            candidate = (
                draft.model_dump(mode="python")
                if isinstance(draft, DayPlanOutput)
                else dict(draft)
            )
            validated = DayPlanOutput.model_validate(candidate)
        except Exception as exc:
            raise ValidationError(
                "The AI draft is malformed and was not applied."
            ) from exc

        request = {
            "raw_input": raw_input,
            "draft": validated.model_dump(mode="json"),
        }

        def mutation() -> dict:
            result = self.planner.save_plan(validated, raw_input, self.user_id)
            result["sync_revision"] = self._record_plan_state(
                int(result["plan_id"]), tasks=True,
            )
            plan = self.db.get_plan_by_id(int(result["plan_id"]), self.user_id)
            result["schedule_revision"] = int(plan.get("schedule_revision") or 0)
            return result

        return self._idempotent(
            operation="ai_plan.apply",
            idempotency_key=idempotency_key,
            request=request,
            mutation=mutation,
        )


class AnalyticsApplicationService(PrincipalService):
    def profile(self, *, window_days: int = 30, language: str = "en") -> dict:
        if not 1 <= int(window_days) <= 365:
            raise ValidationError("Analytics window must be between 1 and 365 days.")
        if language not in {"ar", "en"}:
            raise ValidationError("Analytics language must be 'ar' or 'en'.")
        profile = AnalyticsEngine(self.db).build_profile(
            self.user_id, window_days=int(window_days), language=language,
        )
        return AnalyticsFormatter().to_dict(profile)


class AICoachingApplicationService(PrincipalService):
    """Principal-bound recommendation, voice, and conversational AI access."""

    def __init__(self, db: Database, principal: AuthenticatedPrincipal) -> None:
        super().__init__(db, principal)
        self.recommendations = RecommendationService(db)
        self._voice_limiter = DatabaseRateLimiter(
            db,
            scope="ai.voice_transcription",
            max_requests=10,
            window_seconds=60,
        )

    @staticmethod
    def _translate_rate_limit(exc: RateLimitExceeded) -> RateLimited:
        return RateLimited(retry_after_seconds=exc.retry_after_seconds)

    def recommendations_for_today(self) -> dict:
        try:
            output = self.recommendations.get_recommendations_for_today(self.user_id)
        except RateLimitExceeded as exc:
            raise self._translate_rate_limit(exc) from exc
        except ValueError as exc:
            raise ResourceNotFound("No eligible schedule was found for coaching.") from exc
        return output.model_dump(mode="json")

    def recommendations_for_plan(self, plan_id: int) -> dict:
        self._require_plan(int(plan_id))
        try:
            output = self.recommendations.get_recommendations_for_plan(
                int(plan_id), self.user_id,
            )
        except RateLimitExceeded as exc:
            raise self._translate_rate_limit(exc) from exc
        except ValueError as exc:
            raise ResourceNotFound("No eligible schedule was found for coaching.") from exc
        return output.model_dump(mode="json")

    def transcribe_voice(self, audio_bytes: bytes, *, mime_type: str = "audio/wav") -> str:
        from voice_service import VoiceTranscriptionError, transcribe_audio

        try:
            self._voice_limiter.check(self.user_id)
            return transcribe_audio(audio_bytes, mime_type=mime_type)
        except RateLimitExceeded as exc:
            raise self._translate_rate_limit(exc) from exc
        except VoiceTranscriptionError as exc:
            raise ExternalProviderFailure(
                "The audio provider could not transcribe this recording."
            ) from exc

    def create_chatbot(self):
        """Create a stateful chat bound to this principal and database."""
        from chatbot_service import ChatbotService

        return ChatbotService(self.user_id, db=self.db)

    def chat(self, message: str) -> str:
        """Return one provider-backed chat reply within a hard total deadline."""
        from chatbot_service import send_message_bounded

        return send_message_bounded(self.user_id, message, db=self.db)


class CoachAIApplication:
    """One request-scoped aggregate assembled from a trusted principal."""

    def __init__(self, db: Database, principal: AuthenticatedPrincipal) -> None:
        self.db = db
        self.principal = principal
        self.tasks = TaskApplicationService(db, principal)
        self.scheduling = SchedulingApplicationService(db, principal)
        self.notifications = NotificationApplicationService(db, principal)
        self.calendar = CalendarApplicationService(db, principal)
        self.account = AccountApplicationService(db, principal)
        self.planning = AIPlanningApplicationService(db, principal)
        self.coaching = AICoachingApplicationService(db, principal)
        self.analytics = AnalyticsApplicationService(db, principal)
        self.sync = MobileRepository(db)

    def sync_since(self, cursor: int, *, limit: int = 500) -> dict:
        return self.sync.sync_since(self.principal.user_id, cursor, limit=limit)

    def full_resync(self) -> dict:
        return self.sync.full_snapshot(self.principal.user_id)
