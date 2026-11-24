"""Persistence adapter for the notification domain.

The repository reuses CoachAI's existing ``Database`` connection so callers can
insert outbox rows inside the exact same transaction as domain mutations.
"""

from __future__ import annotations

from datetime import datetime, timedelta
import json
from typing import Any, Mapping, Optional, TYPE_CHECKING
import uuid
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from database import OwnershipError

from .domain import (
    DeliveryChannel,
    DomainEvent,
    InterruptionLevel,
    NotificationDecision,
    NotificationType,
    RiskState,
    Tone,
    utc_now_naive,
)

if TYPE_CHECKING:
    from database import Database


PREFERENCE_DEFAULTS = {
    "tone": Tone.NEUTRAL.value,
    "default_reminder_lead_minutes": 10,
    "quiet_hours_start": None,
    "quiet_hours_end": None,
    "daily_summary_enabled": 0,
    "coach_checkins_enabled": 0,
    "privacy_mode": "show_title",
    "sound_enabled": 1,
    "vibration_enabled": 1,
    "multi_device_policy": "primary",
    "locale": "en",
    "time_format": "12h",
}


class NotificationRepository:
    def __init__(self, db: "Database") -> None:
        self.db = db

    @staticmethod
    def _dt(value: datetime | None = None) -> str:
        return (value or utc_now_naive()).isoformat(sep=" ", timespec="microseconds")

    def ensure_user(self, user_id: str) -> None:
        if self.db.fetch_one("SELECT user_id FROM users WHERE user_id=?", (user_id,)) is None:
            raise OwnershipError("Notification user was not found or is not owned by the caller.")

    def ensure_task(self, user_id: str, task_id: int) -> dict:
        task = self.db.get_task(int(task_id), user_id)
        if task is None:
            raise OwnershipError("Notification task was not found or is not owned by the caller.")
        return task

    def _outbox_expired_status(self) -> str:
        """Use EXPIRED where supported; Patch 1 SQLite schemas map it to CANCELLED."""
        if not self.db.is_sqlite:
            return "expired"
        row = self.db.fetch_one(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='notification_outbox'"
        )
        return "expired" if row and "'expired'" in str(row.get("sql") or "") else "cancelled"

    # ------------------------------------------------------------------
    # Preferences
    # ------------------------------------------------------------------
    def get_preferences(self, user_id: str, *, create: bool = True) -> Optional[dict]:
        self.ensure_user(user_id)
        row = self.db.fetch_one(
            """SELECT np.*,u.timezone FROM notification_preferences np
               JOIN users u ON u.user_id=np.user_id WHERE np.user_id=?""",
            (user_id,),
        )
        if row is not None or not create:
            return row
        values = PREFERENCE_DEFAULTS
        self.db.execute(
            """INSERT INTO notification_preferences
               (user_id,tone,default_reminder_lead_minutes,quiet_hours_start,
                quiet_hours_end,daily_summary_enabled,coach_checkins_enabled,
                privacy_mode,sound_enabled,vibration_enabled,multi_device_policy,
                locale,time_format)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(user_id) DO NOTHING""",
            (
                user_id, values["tone"], values["default_reminder_lead_minutes"],
                values["quiet_hours_start"], values["quiet_hours_end"],
                values["daily_summary_enabled"], values["coach_checkins_enabled"],
                values["privacy_mode"], values["sound_enabled"],
                values["vibration_enabled"], values["multi_device_policy"],
                values["locale"], values["time_format"],
            ),
        )
        self.db.commit()
        return self.get_preferences(user_id, create=False)

    def update_preferences(self, user_id: str, **changes: Any) -> dict:
        self.ensure_user(user_id)
        allowed = set(PREFERENCE_DEFAULTS)
        unknown = set(changes) - allowed
        if unknown:
            raise ValueError(f"Unsupported notification preference(s): {', '.join(sorted(unknown))}.")
        current = self.get_preferences(user_id)
        merged = {key: changes.get(key, current[key]) for key in PREFERENCE_DEFAULTS}
        # FastAPI validates these fields as datetime.time values. Persist their
        # stable ISO representation so SQLite and PostgreSQL receive the same
        # wire value instead of a driver-specific Python object.
        for key in ("quiet_hours_start", "quiet_hours_end"):
            value = merged[key]
            if value is not None and hasattr(value, "isoformat"):
                merged[key] = value.isoformat()
        if merged["tone"] not in {item.value for item in Tone}:
            raise ValueError("Invalid notification tone.")
        lead = merged["default_reminder_lead_minutes"]
        if lead not in {None, 0, 5, 10, 15, 30}:
            raise ValueError("Reminder lead must be OFF, 5, 10, 15, or 30 minutes.")
        if merged["privacy_mode"] not in {"show_title", "hide_title", "generic"}:
            raise ValueError("Invalid lock-screen privacy mode.")
        if merged["multi_device_policy"] not in {"all", "primary", "selected"}:
            raise ValueError("Invalid multi-device notification policy.")
        if merged["locale"] not in {"ar", "en"}:
            raise ValueError("Notification locale must be 'ar' or 'en'.")
        if merged["time_format"] not in {"12h", "24h"}:
            raise ValueError("Time format must be '12h' or '24h'.")
        for key in ("daily_summary_enabled", "coach_checkins_enabled", "sound_enabled", "vibration_enabled"):
            merged[key] = 1 if bool(merged[key]) else 0
        self.db.execute(
            """INSERT INTO notification_preferences
               (user_id,tone,default_reminder_lead_minutes,quiet_hours_start,
                quiet_hours_end,daily_summary_enabled,coach_checkins_enabled,
                privacy_mode,sound_enabled,vibration_enabled,multi_device_policy,
                locale,time_format,updated_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
               ON CONFLICT(user_id) DO UPDATE SET
                 tone=excluded.tone,
                 default_reminder_lead_minutes=excluded.default_reminder_lead_minutes,
                 quiet_hours_start=excluded.quiet_hours_start,
                 quiet_hours_end=excluded.quiet_hours_end,
                 daily_summary_enabled=excluded.daily_summary_enabled,
                 coach_checkins_enabled=excluded.coach_checkins_enabled,
                 privacy_mode=excluded.privacy_mode,
                 sound_enabled=excluded.sound_enabled,
                 vibration_enabled=excluded.vibration_enabled,
                 multi_device_policy=excluded.multi_device_policy,
                 locale=excluded.locale,
                 time_format=excluded.time_format,
                 updated_at=CURRENT_TIMESTAMP""",
            (
                user_id, merged["tone"], merged["default_reminder_lead_minutes"],
                merged["quiet_hours_start"], merged["quiet_hours_end"],
                merged["daily_summary_enabled"], merged["coach_checkins_enabled"],
                merged["privacy_mode"], merged["sound_enabled"],
                merged["vibration_enabled"], merged["multi_device_policy"],
                merged["locale"], merged["time_format"],
            ),
        )
        self.db.commit()
        return self.get_preferences(user_id, create=False)

    def get_type_preference(
        self, user_id: str, notification_type: NotificationType | str,
    ) -> Optional[dict]:
        self.ensure_user(user_id)
        return self.db.fetch_one(
            """SELECT * FROM notification_type_preferences
               WHERE user_id=? AND notification_type=?""",
            (user_id, NotificationType(notification_type).value),
        )

    def set_type_preference(
        self,
        user_id: str,
        notification_type: NotificationType | str,
        *,
        enabled: bool = True,
        preferred_channel: DeliveryChannel | str = DeliveryChannel.IN_APP,
        lead_minutes: Optional[int] = None,
        cooldown_seconds: int = 0,
        interruption_level: InterruptionLevel | str = InterruptionLevel.PASSIVE,
    ) -> dict:
        self.ensure_user(user_id)
        kind = NotificationType(notification_type).value
        channel = DeliveryChannel(preferred_channel).value
        interruption = InterruptionLevel(interruption_level).value
        if lead_minutes not in {None, 0, 5, 10, 15, 30}:
            raise ValueError("Lead time must be OFF, 5, 10, 15, or 30 minutes.")
        if int(cooldown_seconds) < 0:
            raise ValueError("Cooldown cannot be negative.")
        self.db.execute(
            """INSERT INTO notification_type_preferences
               (user_id,notification_type,enabled,preferred_channel,lead_minutes,
                cooldown_seconds,interruption_level,updated_at)
               VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
               ON CONFLICT(user_id,notification_type) DO UPDATE SET
                 enabled=excluded.enabled,
                 preferred_channel=excluded.preferred_channel,
                 lead_minutes=excluded.lead_minutes,
                 cooldown_seconds=excluded.cooldown_seconds,
                 interruption_level=excluded.interruption_level,
                 updated_at=CURRENT_TIMESTAMP""",
            (
                user_id, kind, 1 if enabled else 0, channel, lead_minutes,
                int(cooldown_seconds), interruption,
            ),
        )
        self.db.commit()
        return self.get_type_preference(user_id, kind)

    # ------------------------------------------------------------------
    # Devices
    # ------------------------------------------------------------------
    def _lock_device_registration(
        self,
        *,
        device_id: str,
        provider: str,
        push_token: Optional[str],
    ) -> None:
        if not self.db.is_postgresql:
            return
        identities = {f"device:{device_id}"}
        if push_token:
            identities.add(f"token:{provider}:{push_token}")
        # A stable order prevents two registrations that exchange identities
        # from acquiring the same advisory locks in opposite order.
        for identity in sorted(identities):
            self.db.fetch_one(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0)) AS acquired",
                (f"coachai:notification:{identity}",),
            )

    def register_device(
        self,
        user_id: str,
        *,
        device_id: str,
        platform: str,
        provider: str,
        push_token: Optional[str] = None,
        app_identifier: Optional[str] = None,
        provider_metadata: Optional[Mapping[str, Any]] = None,
        app_version: Optional[str] = None,
        locale: str = "en",
        timezone_name: str = "UTC",
        is_primary: bool = False,
        is_enabled: bool = True,
    ) -> dict:
        self.ensure_user(user_id)
        platform = str(platform).lower()
        provider = str(provider).lower()
        if platform not in {"ios", "android"}:
            raise ValueError("Device platform must be ios or android.")
        if provider not in {"apns", "fcm", "none"}:
            raise ValueError("Device provider must be apns, fcm, or none.")
        if locale not in {"ar", "en"}:
            raise ValueError("Device locale must be ar or en.")
        timezone_name = str(timezone_name or "").strip()
        try:
            ZoneInfo(timezone_name)
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise ValueError("Device timezone must be a valid IANA timezone name.") from exc
        with self.db.transaction():
            self._lock_device_registration(
                device_id=device_id,
                provider=provider,
                push_token=push_token,
            )
            existing = self.db.fetch_one(
                "SELECT user_id FROM notification_devices WHERE device_id=?",
                (device_id,),
            )
            if existing is not None and existing["user_id"] != user_id:
                raise OwnershipError("Device is already registered to another user.")
            if push_token:
                token_row = self.db.fetch_one(
                    """SELECT device_id,user_id FROM notification_devices
                       WHERE provider=? AND push_token=?""",
                    (provider, push_token),
                )
                if token_row is not None and token_row["user_id"] != user_id:
                    raise OwnershipError("Push token is already registered to another user.")
                if token_row is not None and token_row["device_id"] != device_id:
                    # Firebase can rotate an installation identity while
                    # retaining the same token. Keep one canonical row.
                    self.db.execute(
                        "DELETE FROM notification_devices WHERE device_id=? AND user_id=?",
                        (token_row["device_id"], user_id),
                    )
            if is_primary:
                self.db.execute(
                    "UPDATE notification_devices SET is_primary=0 WHERE user_id=?",
                    (user_id,),
                )
            result = self.db.execute(
                """INSERT INTO notification_devices
                   (device_id,user_id,platform,provider,push_token,app_identifier,provider_metadata,app_version,locale,
                    timezone,is_primary,is_enabled,last_seen_at,token_invalidated_at,updated_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,NULL,CURRENT_TIMESTAMP)
                   ON CONFLICT(device_id) DO UPDATE SET
                     platform=excluded.platform,provider=excluded.provider,
                     push_token=excluded.push_token,app_identifier=excluded.app_identifier,
                     provider_metadata=excluded.provider_metadata,app_version=excluded.app_version,
                      locale=excluded.locale,timezone=excluded.timezone,
                      is_primary=excluded.is_primary,is_enabled=excluded.is_enabled,
                      last_seen_at=CURRENT_TIMESTAMP,token_invalidated_at=NULL,
                      updated_at=CURRENT_TIMESTAMP
                    WHERE notification_devices.user_id=excluded.user_id""",
                (
                    device_id, user_id, platform, provider, push_token, app_identifier,
                    json.dumps(dict(provider_metadata or {}), sort_keys=True), app_version,
                    locale, timezone_name, 1 if is_primary else 0, 1 if is_enabled else 0,
                ),
            )
            if result.rowcount != 1:
                raise OwnershipError("Device is already registered to another user.")
        return self.get_device(user_id, device_id)

    def get_device(self, user_id: str, device_id: str) -> dict:
        row = self.db.fetch_one(
            "SELECT * FROM notification_devices WHERE device_id=? AND user_id=?",
            (device_id, user_id),
        )
        if row is None:
            raise OwnershipError("Device was not found or is not owned by the caller.")
        return row

    def list_devices(self, user_id: str, *, enabled_only: bool = False) -> list[dict]:
        self.ensure_user(user_id)
        suffix = " AND is_enabled=1" if enabled_only else ""
        return self.db.fetch_all(
            f"SELECT * FROM notification_devices WHERE user_id=?{suffix} ORDER BY is_primary DESC,created_at",
            (user_id,),
        )

    def invalidate_device_token(self, user_id: str, device_id: str) -> None:
        self.get_device(user_id, device_id)
        self.db.execute(
            """UPDATE notification_devices
               SET push_token=NULL,is_enabled=0,token_invalidated_at=CURRENT_TIMESTAMP,
                   updated_at=CURRENT_TIMESTAMP
               WHERE device_id=? AND user_id=?""",
            (device_id, user_id),
        )
        self.db.commit()

    def acknowledge_schedule_revision(
        self, user_id: str, device_id: str, sync_revision: int,
    ) -> None:
        self.get_device(user_id, device_id)
        current = self.get_sync_revision(user_id)
        if int(sync_revision) < 0 or int(sync_revision) > current:
            raise ValueError("Acknowledged notification revision is invalid.")
        self.db.execute(
            """UPDATE notification_devices
               SET last_acknowledged_schedule_revision=CASE
                       WHEN last_acknowledged_schedule_revision<? THEN ?
                       ELSE last_acknowledged_schedule_revision END,
                   last_seen_at=CURRENT_TIMESTAMP,
                   updated_at=CURRENT_TIMESTAMP
               WHERE device_id=? AND user_id=?""",
            (int(sync_revision), int(sync_revision), device_id, user_id),
        )
        self.db.commit()

    # ------------------------------------------------------------------
    # Outbox and synchronization
    # ------------------------------------------------------------------
    def get_sync_revision(self, user_id: str) -> int:
        self.ensure_user(user_id)
        row = self.db.fetch_one(
            "SELECT sync_revision FROM notification_sync_state WHERE user_id=?",
            (user_id,),
        )
        return int(row["sync_revision"]) if row else 0

    def _increment_sync_revision_no_commit(self, user_id: str) -> int:
        self.db.execute(
            """INSERT INTO notification_sync_state(user_id,sync_revision,updated_at)
               VALUES (?,1,CURRENT_TIMESTAMP)
               ON CONFLICT(user_id) DO UPDATE SET
                 sync_revision=notification_sync_state.sync_revision+1,
                 updated_at=CURRENT_TIMESTAMP""",
            (user_id,),
        )
        row = self.db.fetch_one(
            "SELECT sync_revision FROM notification_sync_state WHERE user_id=?",
            (user_id,),
        )
        return int(row["sync_revision"])

    def enqueue_event(self, event: DomainEvent, *, bump_sync_revision: bool = False) -> tuple[str, bool, int]:
        self.ensure_user(event.user_id)
        result = self.db.execute(
            """INSERT INTO notification_outbox
               (outbox_id,user_id,event_type,task_id,segment_id,scheduled_date,
                segment_index,schedule_revision,schedule_operation_id,event_payload,
                idempotency_key,status,attempts,available_at,expires_at,created_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,'queued',0,?,?,?)
               ON CONFLICT(user_id,idempotency_key) DO NOTHING""",
            (
                event.event_id, event.user_id, event.event_type.value, event.task_id,
                event.segment_id,
                event.scheduled_date.isoformat() if event.scheduled_date else None,
                event.segment_index, event.schedule_revision,
                event.schedule_operation_id,
                json.dumps(dict(event.payload), ensure_ascii=False, sort_keys=True, default=str),
                event.idempotency_key, self._dt(event.occurred_at),
                self._dt(datetime.fromisoformat(str(event.payload["expires_at"])))
                if event.payload.get("expires_at") else None,
                self._dt(event.occurred_at),
            ),
        )
        created = result.rowcount == 1
        revision = self.get_sync_revision(event.user_id)
        if created and bump_sync_revision:
            revision = self._increment_sync_revision_no_commit(event.user_id)
        return event.event_id, created, revision

    def list_outbox(self, user_id: str, *, status: Optional[str] = None) -> list[dict]:
        self.ensure_user(user_id)
        if status is None:
            return self.db.fetch_all(
                "SELECT * FROM notification_outbox WHERE user_id=? ORDER BY sequence_id",
                (user_id,),
            )
        return self.db.fetch_all(
            """SELECT * FROM notification_outbox
               WHERE user_id=? AND status=? ORDER BY sequence_id""",
            (user_id, status),
        )

    def claim_outbox(
        self,
        *,
        limit: int = 50,
        now: Optional[datetime] = None,
        stale_after_seconds: int = 300,
        worker_id: Optional[str] = None,
        max_attempts: int = 5,
    ) -> list[dict]:
        current = now or utc_now_naive()
        moment = self._dt(current)
        stale_before = self._dt(current - timedelta(seconds=max(1, int(stale_after_seconds))))
        owner = worker_id or uuid.uuid4().hex
        claimed: list[dict] = []
        with self.db.transaction():
            expired_status = self._outbox_expired_status()
            self.db.execute(
                """UPDATE notification_outbox
                   SET status=?,locked_at=NULL,locked_by=NULL,
                       last_error='expired_before_processing',processed_at=?
                   WHERE status IN ('queued','failed') AND expires_at IS NOT NULL
                     AND expires_at<=?""",
                (expired_status, moment, moment),
            )
            # A process may die after claiming work. Release an abandoned lock
            # so retry/dead-letter policy can observe it instead of losing the
            # event forever.
            self.db.execute(
                """UPDATE notification_outbox
                   SET status='failed',locked_at=NULL,locked_by=NULL,
                       last_error=COALESCE(last_error,'stale_processing_lock_recovered'),
                       available_at=?
                   WHERE status='processing' AND locked_at<?""",
                (moment, stale_before),
            )
            candidates = self.db.fetch_all(
                """SELECT * FROM notification_outbox
                   WHERE status IN ('queued','failed') AND attempts < ?
                     AND available_at<=? AND locked_at IS NULL
                   ORDER BY sequence_id LIMIT ?""",
                (int(max_attempts), moment, int(limit)),
            )
            for row in candidates:
                result = self.db.execute(
                    """UPDATE notification_outbox
                       SET status='processing',attempts=attempts+1,locked_at=?,locked_by=?
                       WHERE outbox_id=? AND status IN ('queued','failed') AND locked_at IS NULL""",
                    (moment, owner, row["outbox_id"]),
                )
                if result.rowcount == 1:
                    claimed_row = dict(row)
                    claimed_row["status"] = "processing"
                    claimed_row["attempts"] = int(row["attempts"]) + 1
                    claimed_row["locked_at"] = moment
                    claimed_row["locked_by"] = owner
                    claimed.append(claimed_row)
        return claimed

    def queue_lag_seconds(self, *, now: Optional[datetime] = None) -> dict[str, float]:
        """Return age of the oldest currently-ready outbox/push work."""

        current = now or utc_now_naive()
        if current.tzinfo is not None:
            current = current.replace(tzinfo=None)
        outbox = self.db.fetch_one(
            """SELECT MIN(created_at) AS oldest FROM notification_outbox
               WHERE status IN ('queued','failed') AND available_at<=?""",
            (current,),
        )
        push = self.db.fetch_one(
            """SELECT MIN(queued_at) AS oldest FROM notification_deliveries
               WHERE channel='push' AND status IN ('queued','retrying')
                 AND COALESCE(next_attempt_at,queued_at)<=?""",
            (current,),
        )

        def age(row: Optional[dict]) -> float:
            raw = row.get("oldest") if row else None
            if not raw:
                return 0.0
            try:
                parsed = raw if isinstance(raw, datetime) else datetime.fromisoformat(str(raw))
            except (TypeError, ValueError):
                return 0.0
            if parsed.tzinfo is not None:
                parsed = parsed.replace(tzinfo=None)
            return max(0.0, (current - parsed).total_seconds())

        return {"outbox": age(outbox), "push": age(push)}

    def mark_outbox_processed(
        self, outbox_id: str, user_id: str, *, worker_id: Optional[str] = None,
    ) -> None:
        owner_clause = " AND locked_by=?" if worker_id is not None else ""
        params: tuple[Any, ...] = (outbox_id, user_id)
        if worker_id is not None:
            params += (worker_id,)
        result = self.db.execute(
            f"""UPDATE notification_outbox
               SET status='processed',processed_at=CURRENT_TIMESTAMP,locked_at=NULL,
                   locked_by=NULL,last_error=NULL
               WHERE outbox_id=? AND user_id=? AND status='processing'{owner_clause}""",
            params,
        )
        if result.rowcount != 1:
            raise OwnershipError("Outbox event was not found or is not owned by the caller.")

    def mark_outbox_failed(
        self,
        outbox_id: str,
        user_id: str,
        error: str,
        *,
        now: Optional[datetime] = None,
        worker_id: Optional[str] = None,
        permanent: bool = False,
        max_attempts: int = 5,
        base_delay_seconds: int = 30,
    ) -> None:
        row = self.db.fetch_one(
            "SELECT attempts,locked_by FROM notification_outbox WHERE outbox_id=? AND user_id=?",
            (outbox_id, user_id),
        )
        if row is None or (worker_id is not None and row.get("locked_by") != worker_id):
            raise OwnershipError("Outbox event was not found, owned, or leased by the caller.")
        attempts = int(row["attempts"])
        terminal = permanent or attempts >= int(max_attempts)
        delay = min(900, max(1, int(base_delay_seconds)) * (2 ** max(0, attempts - 1)))
        retry_at = (now or utc_now_naive()) + timedelta(seconds=delay)
        result = self.db.execute(
            """UPDATE notification_outbox
               SET status='failed',locked_at=NULL,locked_by=NULL,last_error=?,
                   available_at=?,attempts=?
               WHERE outbox_id=? AND user_id=?""",
            (
                str(error)[:2000], self._dt(retry_at),
                int(max_attempts) if terminal else attempts, outbox_id, user_id,
            ),
        )
        if result.rowcount != 1:
            raise OwnershipError("Outbox event was not found or is not owned by the caller.")
        self.db.commit()

    def mark_outbox_expired(self, outbox_id: str, user_id: str, *, worker_id: str) -> None:
        status = self._outbox_expired_status()
        result = self.db.execute(
            """UPDATE notification_outbox
               SET status=?,processed_at=CURRENT_TIMESTAMP,locked_at=NULL,
                   locked_by=NULL,last_error='expired_before_processing'
               WHERE outbox_id=? AND user_id=? AND status='processing' AND locked_by=?""",
            (status, outbox_id, user_id, worker_id),
        )
        if result.rowcount != 1:
            raise OwnershipError("Outbox event was not found, owned, or leased by the caller.")

    # ------------------------------------------------------------------
    # Notifications and deliveries
    # ------------------------------------------------------------------
    def cooldown_active(
        self,
        user_id: str,
        notification_type: NotificationType | str,
        cooldown_seconds: int,
        *,
        task_id: Optional[int] = None,
        now: Optional[datetime] = None,
    ) -> bool:
        if int(cooldown_seconds) <= 0:
            return False
        cutoff = self._dt((now or utc_now_naive()) - timedelta(seconds=int(cooldown_seconds)))
        sql = (
            """SELECT notification_id FROM notifications
               WHERE user_id=? AND notification_type=? AND created_at>=?
                 AND suppression_reason IS NULL"""
        )
        params: tuple[Any, ...] = (user_id, NotificationType(notification_type).value, cutoff)
        if task_id is None:
            sql += " AND task_id IS NULL"
        else:
            sql += " AND task_id=?"
            params += (int(task_id),)
        return self.db.fetch_one(sql + " LIMIT 1", params) is not None

    def recent_template_ids(
        self, user_id: str, notification_type: NotificationType | str, *, limit: int = 5,
    ) -> list[str]:
        rows = self.db.fetch_all(
            """SELECT template_id FROM notifications
               WHERE user_id=? AND notification_type=? AND template_id IS NOT NULL
               ORDER BY created_at DESC LIMIT ?""",
            (user_id, NotificationType(notification_type).value, int(limit)),
        )
        return [str(row["template_id"]) for row in rows]

    def create_notification(
        self,
        event: DomainEvent,
        decision: NotificationDecision,
        *,
        title: str,
        body: str,
        locale: str,
        template_id: str,
        template_version: int,
        now: Optional[datetime] = None,
    ) -> tuple[dict, bool]:
        notification_id = uuid.uuid4().hex
        status = (
            "suppressed" if decision.suppression_reason
            else "expired" if decision.expires_at <= (now or utc_now_naive()).replace(tzinfo=None)
            else "active"
        )
        result = self.db.execute(
            """INSERT INTO notifications
               (notification_id,user_id,notification_type,urgency,interruption_level,
                task_id,segment_id,scheduled_date,segment_index,schedule_revision,
                schedule_operation_id,title,body,locale,template_id,template_version,
                scheduled_for,expires_at,status,dedupe_key,suppression_reason,
                action_metadata,created_at,updated_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
               ON CONFLICT(user_id,dedupe_key) DO NOTHING""",
            (
                notification_id, event.user_id, decision.notification_type.value,
                decision.urgency.value, decision.interruption_level.value,
                event.task_id, event.segment_id,
                event.scheduled_date.isoformat() if event.scheduled_date else None,
                event.segment_index, event.schedule_revision,
                event.schedule_operation_id, title, body, locale, template_id,
                int(template_version), self._dt(decision.scheduled_for),
                self._dt(decision.expires_at), status, decision.dedupe_key,
                decision.suppression_reason,
                json.dumps(dict(decision.action_metadata), ensure_ascii=False, sort_keys=True),
            ),
        )
        created = result.rowcount == 1
        if created:
            revision = self._increment_sync_revision_no_commit(event.user_id)
            self.db.execute(
                "UPDATE notifications SET sync_revision=? WHERE notification_id=? AND user_id=?",
                (revision, notification_id, event.user_id),
            )
        row = self.db.fetch_one(
            "SELECT * FROM notifications WHERE user_id=? AND dedupe_key=?",
            (event.user_id, decision.dedupe_key),
        )
        return row, created

    def create_delivery(
        self,
        user_id: str,
        notification_id: str,
        channel: DeliveryChannel | str,
        *,
        device_id: Optional[str] = None,
        status: str = "queued",
        last_error: Optional[str] = None,
    ) -> tuple[dict, bool]:
        notification = self.get_notification(user_id, notification_id)
        channel_value = DeliveryChannel(channel).value
        device = self.get_device(user_id, device_id) if device_id is not None else None
        target_key = device_id or "account"
        delivery_id = uuid.uuid4().hex
        delivered_at = self._dt() if status == "delivered" else None
        result = self.db.execute(
            """INSERT INTO notification_deliveries
               (delivery_id,notification_id,channel,device_id,target_key,status,
                provider,queued_at,delivered_at,retry_count,next_attempt_at,last_error,created_at,updated_at)
               VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?,0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
               ON CONFLICT(notification_id,channel,target_key) DO NOTHING""",
            (
                delivery_id, notification["notification_id"], channel_value,
                device_id, target_key, status, device.get("provider") if device else None,
                delivered_at, notification["scheduled_for"], last_error,
            ),
        )
        row = self.db.fetch_one(
            """SELECT d.* FROM notification_deliveries d
               JOIN notifications n ON n.notification_id=d.notification_id
               WHERE d.notification_id=? AND d.channel=? AND d.target_key=? AND n.user_id=?""",
            (notification_id, channel_value, target_key, user_id),
        )
        return row, result.rowcount == 1

    def get_delivery(self, user_id: str, delivery_id: str) -> dict:
        row = self.db.fetch_one(
            """SELECT d.* FROM notification_deliveries d
               JOIN notifications n ON n.notification_id=d.notification_id
               WHERE d.delivery_id=? AND n.user_id=?""",
            (delivery_id, user_id),
        )
        if row is None:
            raise OwnershipError("Delivery was not found or is not owned by the caller.")
        return row

    def list_push_deliveries(self, user_id: str, *, status: Optional[str] = None) -> list[dict]:
        self.ensure_user(user_id)
        suffix = " AND d.status=?" if status else ""
        params: tuple[Any, ...] = (user_id,) + ((status,) if status else ())
        return self.db.fetch_all(
            f"""SELECT d.* FROM notification_deliveries d
                JOIN notifications n ON n.notification_id=d.notification_id
                WHERE n.user_id=? AND d.channel='push'{suffix}
                ORDER BY d.created_at,d.delivery_id""",
            params,
        )

    def claim_push_deliveries(
        self, *, limit: int = 100, now: Optional[datetime] = None,
        worker_id: Optional[str] = None, stale_after_seconds: int = 300,
        max_attempts: int = 5,
    ) -> list[dict]:
        current = (now or utc_now_naive()).replace(tzinfo=None)
        moment = self._dt(current)
        stale = self._dt(current - timedelta(seconds=max(1, int(stale_after_seconds))))
        owner = worker_id or uuid.uuid4().hex
        claimed: list[dict] = []
        with self.db.transaction():
            self.db.execute(
                """UPDATE notification_deliveries SET locked_at=NULL,locked_by=NULL,
                       last_error=COALESCE(last_error,'stale_push_lease_recovered')
                   WHERE channel='push' AND status='queued' AND locked_at<?""",
                (stale,),
            )
            self.db.execute(
                """UPDATE notification_deliveries SET status='cancelled',cancelled_at=?,
                       locked_at=NULL,locked_by=NULL,last_error='notification_not_actionable'
                   WHERE channel='push' AND status='queued' AND notification_id IN (
                       SELECT notification_id FROM notifications
                       WHERE status<>'active' OR expires_at<=?
                   )""",
                (moment, moment),
            )
            candidates = self.db.fetch_all(
                """SELECT d.*,n.user_id,n.notification_type,n.task_id,n.segment_id,
                          n.segment_index,n.schedule_revision,n.schedule_operation_id,
                          n.title,n.body,n.action_metadata,n.expires_at,n.status AS notification_status,
                          nd.platform,nd.push_token,nd.app_identifier,nd.is_enabled,
                          nd.token_invalidated_at
                   FROM notification_deliveries d
                   JOIN notifications n ON n.notification_id=d.notification_id
                   JOIN notification_devices nd ON nd.device_id=d.device_id AND nd.user_id=n.user_id
                   WHERE d.channel='push' AND d.status='queued' AND d.retry_count<?
                     AND COALESCE(d.next_attempt_at,d.queued_at)<=? AND d.locked_at IS NULL
                     AND n.status='active' AND n.scheduled_for<=? AND n.expires_at>?
                     AND nd.is_enabled=1 AND nd.push_token IS NOT NULL
                     AND nd.token_invalidated_at IS NULL
                   ORDER BY d.queued_at,d.delivery_id LIMIT ?""",
                (int(max_attempts), moment, moment, moment, int(limit)),
            )
            for row in candidates:
                result = self.db.execute(
                    """UPDATE notification_deliveries
                       SET locked_at=?,locked_by=?,last_attempt_at=?,retry_count=retry_count+1,
                           updated_at=CURRENT_TIMESTAMP
                       WHERE delivery_id=? AND status='queued' AND locked_at IS NULL""",
                    (moment, owner, moment, row["delivery_id"]),
                )
                if result.rowcount == 1:
                    item = dict(row)
                    item["locked_at"] = moment
                    item["locked_by"] = owner
                    item["retry_count"] = int(row["retry_count"]) + 1
                    claimed.append(item)
        return claimed

    def mark_push_accepted(
        self, user_id: str, delivery_id: str, *, worker_id: str,
        provider_message_id: Optional[str], provider: str,
    ) -> None:
        self.get_delivery(user_id, delivery_id)
        result = self.db.execute(
            """UPDATE notification_deliveries
               SET status='sent',provider=?,provider_message_id=?,sent_at=CURRENT_TIMESTAMP,
                   accepted_at=CURRENT_TIMESTAMP,locked_at=NULL,locked_by=NULL,last_error=NULL,
                   error_classification=NULL,updated_at=CURRENT_TIMESTAMP
               WHERE delivery_id=? AND locked_by=? AND status='queued'""",
            (provider, provider_message_id, delivery_id, worker_id),
        )
        if result.rowcount != 1:
            raise OwnershipError("Delivery was not found, owned, or leased by the caller.")

    def mark_push_failed(
        self, user_id: str, delivery_id: str, *, worker_id: str,
        classification: str, error_code: str, now: Optional[datetime] = None,
        retry_after_seconds: Optional[int] = None, max_attempts: int = 5,
    ) -> dict:
        delivery = self.get_delivery(user_id, delivery_id)
        if delivery.get("locked_by") != worker_id:
            raise OwnershipError("Delivery was not leased by the caller.")
        attempts = int(delivery["retry_count"])
        retryable = classification in {"transient", "rate_limited", "unknown"} and attempts < int(max_attempts)
        delay = min(900, 30 * (2 ** max(0, attempts - 1)))
        if retry_after_seconds is not None:
            delay = min(900, max(delay, max(1, int(retry_after_seconds))))
        next_attempt = (now or utc_now_naive()).replace(tzinfo=None) + timedelta(seconds=delay)
        self.db.execute(
            """UPDATE notification_deliveries
               SET status=?,next_attempt_at=?,locked_at=NULL,locked_by=NULL,last_error=?,
                   error_classification=?,updated_at=CURRENT_TIMESTAMP
               WHERE delivery_id=? AND locked_by=?""",
            (
                "queued" if retryable else "failed", self._dt(next_attempt),
                str(error_code)[:500], classification, delivery_id, worker_id,
            ),
        )
        if classification == "permanent_token" and delivery.get("device_id"):
            self.invalidate_device_token(user_id, str(delivery["device_id"]))
        return {"retrying": retryable, "next_attempt_at": next_attempt if retryable else None}

    def cancel_push_delivery(
        self, user_id: str, delivery_id: str, *, worker_id: str, reason: str,
    ) -> None:
        self.get_delivery(user_id, delivery_id)
        result = self.db.execute(
            """UPDATE notification_deliveries SET status='cancelled',cancelled_at=CURRENT_TIMESTAMP,
                   locked_at=NULL,locked_by=NULL,last_error=?,updated_at=CURRENT_TIMESTAMP
               WHERE delivery_id=? AND locked_by=? AND status='queued'""",
            (str(reason)[:500], delivery_id, worker_id),
        )
        if result.rowcount != 1:
            raise OwnershipError("Delivery was not found, owned, or leased by the caller.")

    def get_notification(self, user_id: str, notification_id: str) -> dict:
        row = self.db.fetch_one(
            "SELECT * FROM notifications WHERE notification_id=? AND user_id=?",
            (notification_id, user_id),
        )
        if row is None:
            raise OwnershipError("Notification was not found or is not owned by the caller.")
        return row

    def list_notifications(
        self,
        user_id: str,
        *,
        unread_only: bool = False,
        include_suppressed: bool = False,
        limit: int = 100,
    ) -> list[dict]:
        self.ensure_user(user_id)
        conditions = ["user_id=?"]
        params: list[Any] = [user_id]
        if unread_only:
            conditions.append("read_at IS NULL")
        if not include_suppressed:
            conditions.append("status NOT IN ('suppressed','cancelled','expired')")
        params.append(int(limit))
        return self.db.fetch_all(
            f"""SELECT * FROM notifications WHERE {' AND '.join(conditions)}
                ORDER BY created_at DESC,notification_id DESC LIMIT ?""",
            tuple(params),
        )

    def unread_count(self, user_id: str) -> int:
        self.ensure_user(user_id)
        row = self.db.fetch_one(
            """SELECT COUNT(*) AS n FROM notifications
               WHERE user_id=? AND read_at IS NULL AND status='active'""",
            (user_id,),
        )
        return int(row["n"])

    def mark_read(self, user_id: str, notification_id: str) -> None:
        self.get_notification(user_id, notification_id)
        now = self._dt()
        result = self.db.execute(
            """UPDATE notifications SET read_at=COALESCE(read_at,?),updated_at=CURRENT_TIMESTAMP
               WHERE notification_id=? AND user_id=? AND read_at IS NULL""",
            (now, notification_id, user_id),
        )
        if result.rowcount:
            revision = self._increment_sync_revision_no_commit(user_id)
            self.db.execute(
                "UPDATE notifications SET sync_revision=? WHERE notification_id=? AND user_id=?",
                (revision, notification_id, user_id),
            )
        self.db.execute(
            """UPDATE notification_deliveries SET read_at=COALESCE(read_at,?),updated_at=CURRENT_TIMESTAMP
               WHERE notification_id=? AND channel='in_app'""",
            (now, notification_id),
        )
        self.db.commit()

    def mark_acted(self, user_id: str, notification_id: str) -> None:
        self.get_notification(user_id, notification_id)
        now = self._dt()
        result = self.db.execute(
            """UPDATE notifications SET acted_at=COALESCE(acted_at,?),read_at=COALESCE(read_at,?),
                   updated_at=CURRENT_TIMESTAMP WHERE notification_id=? AND user_id=?
                   AND acted_at IS NULL""",
            (now, now, notification_id, user_id),
        )
        if result.rowcount:
            revision = self._increment_sync_revision_no_commit(user_id)
            self.db.execute(
                "UPDATE notifications SET sync_revision=? WHERE notification_id=? AND user_id=?",
                (revision, notification_id, user_id),
            )
        self.db.execute(
            """UPDATE notification_deliveries SET acted_at=COALESCE(acted_at,?),
                   read_at=COALESCE(read_at,?),updated_at=CURRENT_TIMESTAMP
               WHERE notification_id=?""",
            (now, now, notification_id),
        )
        self.db.commit()

    def expire_due(self, *, now: Optional[datetime] = None) -> int:
        rows = self.db.fetch_all(
            "SELECT notification_id,user_id FROM notifications WHERE status='active' AND expires_at<=?",
            (self._dt(now),),
        )
        for row in rows:
            revision = self._increment_sync_revision_no_commit(row["user_id"])
            self.db.execute(
                """UPDATE notifications SET status='expired',sync_revision=?,updated_at=CURRENT_TIMESTAMP
                   WHERE notification_id=? AND user_id=? AND status='active'""",
                (revision, row["notification_id"], row["user_id"]),
            )
        self.db.commit()
        return len(rows)

    def sync_since(self, user_id: str, revision: int, *, limit: int = 500) -> dict:
        self.ensure_user(user_id)
        if int(revision) < 0:
            raise ValueError("Sync revision cannot be negative.")
        if not 1 <= int(limit) <= 500:
            raise ValueError("Notification sync limit must be between 1 and 500.")
        current = self.get_sync_revision(user_id)
        rows = self.db.fetch_all(
            """SELECT * FROM notifications
               WHERE user_id=? AND sync_revision>? AND sync_revision<=?
               ORDER BY sync_revision,notification_id LIMIT ?""",
            (user_id, int(revision), current, int(limit)),
        )
        checkpoint = int(rows[-1]["sync_revision"]) if rows else current
        return {
            "from_revision": int(revision),
            "revision": checkpoint,
            "current_revision": current,
            "has_more": checkpoint < current,
            "notifications": rows,
        }

    # ------------------------------------------------------------------
    # Provider-independent local schedules and reconciliation
    # ------------------------------------------------------------------
    def delivery_targets(self, user_id: str, *, channel: DeliveryChannel | str) -> list[Optional[str]]:
        preferences = self.get_preferences(user_id)
        channel_value = DeliveryChannel(channel)
        devices = [
            row for row in self.list_devices(user_id, enabled_only=True)
            if row.get("token_invalidated_at") is None
            and (
                channel_value is not DeliveryChannel.PUSH
                or (
                    row.get("provider") in {"fcm", "apns"}
                    and bool(row.get("push_token"))
                )
            )
        ]
        if not devices:
            return [] if channel_value is DeliveryChannel.PUSH else [None]
        policy = str(preferences.get("multi_device_policy") or "primary")
        if policy == "all":
            return [str(row["device_id"]) for row in devices]
        primary = next((row for row in devices if bool(row.get("is_primary"))), devices[0])
        return [str(primary["device_id"])]

    def create_local_schedule(
        self,
        user_id: str,
        notification_id: str,
        *,
        device_id: Optional[str] = None,
    ) -> tuple[dict, bool]:
        notification = self.get_notification(user_id, notification_id)
        if device_id is not None:
            device = self.get_device(user_id, device_id)
        else:
            device = None
        target_key = device_id or "account"
        schedule_id = uuid.uuid4().hex
        payload = {
            "notification_id": notification_id,
            "notification_type": notification["notification_type"],
            "title": notification["title"],
            "body": notification["body"],
            "actions": json.loads(notification.get("action_metadata") or "{}"),
        }
        result = self.db.execute(
            """INSERT INTO notification_local_schedules
               (local_schedule_id,notification_id,user_id,device_id,target_key,task_id,
                segment_id,segment_index,schedule_revision,schedule_operation_id,
                trigger_type,scheduled_fire_at,expires_at,status,payload,locale,timezone)
               VALUES (?,?,?,?,?,?,?,?,?,?,'time',?,?, 'scheduled',?,?,?)
               ON CONFLICT(notification_id,target_key) DO NOTHING""",
            (
                schedule_id, notification_id, user_id, device_id, target_key,
                notification.get("task_id"), notification.get("segment_id"),
                notification.get("segment_index"), notification.get("schedule_revision"),
                notification.get("schedule_operation_id"), notification["scheduled_for"],
                notification["expires_at"], json.dumps(payload, ensure_ascii=False, sort_keys=True),
                device.get("locale") if device else notification["locale"],
                device.get("timezone") if device else self.get_preferences(user_id)["timezone"],
            ),
        )
        row = self.db.fetch_one(
            """SELECT * FROM notification_local_schedules
               WHERE notification_id=? AND target_key=? AND user_id=?""",
            (notification_id, target_key, user_id),
        )
        return row, result.rowcount == 1

    def list_local_schedules(
        self, user_id: str, *, status: Optional[str] = None, task_id: Optional[int] = None,
    ) -> list[dict]:
        self.ensure_user(user_id)
        conditions = ["user_id=?"]
        params: list[Any] = [user_id]
        if status is not None:
            conditions.append("status=?")
            params.append(status)
        if task_id is not None:
            self.ensure_task(user_id, task_id)
            conditions.append("task_id=?")
            params.append(int(task_id))
        return self.db.fetch_all(
            f"""SELECT * FROM notification_local_schedules
                WHERE {' AND '.join(conditions)} ORDER BY scheduled_fire_at,local_schedule_id""",
            tuple(params),
        )

    def cancel_local_schedules(
        self,
        user_id: str,
        *,
        task_id: Optional[int] = None,
        notification_type: NotificationType | str | None = None,
        except_identities: Optional[set[tuple[int, int, int]]] = None,
        reason: str = "reconciled",
    ) -> int:
        self.ensure_user(user_id)
        conditions = ["user_id=?", "status='scheduled'"]
        params: list[Any] = [user_id]
        if task_id is not None:
            conditions.append("task_id=?")
            params.append(int(task_id))
        if notification_type is not None:
            kind = NotificationType(notification_type).value
            conditions.append(
                "notification_id IN (SELECT notification_id FROM notifications "
                "WHERE user_id=? AND notification_type=?)"
            )
            params.extend((user_id, kind))
        rows = self.db.fetch_all(
            f"SELECT * FROM notification_local_schedules WHERE {' AND '.join(conditions)}",
            tuple(params),
        )
        stale = []
        for row in rows:
            identity = (
                int(row.get("task_id") or -1), int(row.get("segment_index") or 0),
                int(row.get("schedule_revision") or 0),
            )
            if except_identities is None or identity not in except_identities:
                stale.append(row)
        for row in stale:
            self.db.execute(
                """UPDATE notification_local_schedules
                   SET status='cancelled',cancelled_at=CURRENT_TIMESTAMP,
                       cancellation_reason=?,updated_at=CURRENT_TIMESTAMP
                   WHERE local_schedule_id=? AND user_id=? AND status='scheduled'""",
                (str(reason)[:200], row["local_schedule_id"], user_id),
            )
            self.db.execute(
                """UPDATE notification_deliveries SET status='cancelled',cancelled_at=CURRENT_TIMESTAMP,
                       last_error=?,updated_at=CURRENT_TIMESTAMP
                   WHERE notification_id=? AND channel='local' AND status='queued'""",
                (str(reason)[:200], row["notification_id"]),
            )
            updated = self.db.execute(
                """UPDATE notifications SET status='cancelled',updated_at=CURRENT_TIMESTAMP
                   WHERE notification_id=? AND user_id=? AND status='active'""",
                (row["notification_id"], user_id),
            )
            if updated.rowcount:
                revision = self._increment_sync_revision_no_commit(user_id)
                self.db.execute(
                    "UPDATE notifications SET sync_revision=? WHERE notification_id=? AND user_id=?",
                    (revision, row["notification_id"], user_id),
                )
        return len(stale)

    def expire_local_schedules(self, *, now: Optional[datetime] = None) -> int:
        result = self.db.execute(
            """UPDATE notification_local_schedules
               SET status='expired',updated_at=CURRENT_TIMESTAMP
               WHERE status='scheduled' AND expires_at<=?""",
            (self._dt(now),),
        )
        self.db.commit()
        return int(result.rowcount or 0)

    # ------------------------------------------------------------------
    # Deadline-risk transition state
    # ------------------------------------------------------------------
    def get_task_notification_state(self, user_id: str, task_id: int) -> Optional[dict]:
        self.ensure_task(user_id, task_id)
        return self.db.fetch_one(
            """SELECT * FROM task_notification_state
               WHERE user_id=? AND task_id=?""",
            (user_id, int(task_id)),
        )

    def set_task_notification_state_no_commit(
        self,
        user_id: str,
        task_id: int,
        state: RiskState | str,
        *,
        transitioned: bool,
    ) -> None:
        self.ensure_task(user_id, task_id)
        risk = RiskState(state).value
        self.db.execute(
            """INSERT INTO task_notification_state
               (user_id,task_id,state,last_evaluated_at,last_transition_at)
               VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
               ON CONFLICT(user_id,task_id) DO UPDATE SET
                 state=excluded.state,last_evaluated_at=CURRENT_TIMESTAMP,
                 last_transition_at=CASE WHEN ?=1 THEN CURRENT_TIMESTAMP
                                         ELSE task_notification_state.last_transition_at END""",
            (user_id, int(task_id), risk, 1 if transitioned else 0),
        )
