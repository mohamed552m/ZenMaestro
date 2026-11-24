"""Thin FastAPI routes over principal-bound CoachAI application services."""

from __future__ import annotations

from datetime import date, time
from typing import Annotated, Any, Callable
from urllib.parse import urlencode

from fastapi import APIRouter, Depends, Header, Path, Query, Request, Response
from fastapi.responses import RedirectResponse

from api_models import (
    CalendarAuthorizationCompleteRequest,
    CalendarAuthorizationResponse,
    CalendarConnectionResponse,
    CalendarItemResponse,
    CalendarSelectionRequest,
    CalendarSyncRequest,
    CoachChatRequest,
    CoachChatResponse,
    VoiceTranscriptionResponse,
    CoachRecommendationResponse,
    ErrorResponse,
    EnsureTodayPlanResponse,
    HomeResponse,
    FuturePlanningApplyResponse,
    FuturePlanningCandidatesResponse,
    FuturePlanningPreviewResponse,
    NotificationPreferencesResponse,
    NotificationPreferencesUpdateRequest,
    NotificationDeviceRegistrationRequest,
    NotificationDeviceResponse,
    NotificationDeviceInvalidationResponse,
    ProfileDisplayNameUpdateRequest,
    ProfileResponse,
    ProfileTimezoneUpdateRequest,
    ProfileTimezoneUpdateResponse,
    PlanningDraftRequest,
    PlanningApplyRequest,
    ScheduleMoveRequest,
    SchedulePlanRequest,
    TaskListResponse,
    TaskCommandRequest,
    TaskCreateRequest,
    TaskResponse,
    TaskUpdateRequest,
)
from domain_validation import MAX_DATABASE_INTEGER, MAX_TASK_LIST_OFFSET
from planning_request import StructuredPlanningRequest


_ERROR_RESPONSES = {
    401: {"model": ErrorResponse},
    403: {"model": ErrorResponse},
    404: {"model": ErrorResponse},
    409: {"model": ErrorResponse},
    422: {"model": ErrorResponse},
    500: {"model": ErrorResponse},
    503: {"model": ErrorResponse},
}


def _protect_private_response(response: Response) -> None:
    response.headers["Cache-Control"] = "no-store"
    response.headers["Pragma"] = "no-cache"
    response.headers["Vary"] = "Authorization"


def create_home_router(application_dependency: Callable[..., Any]) -> APIRouter:
    """Expose the authenticated user's server-resolved local day."""

    router = APIRouter(prefix="/v1", tags=["home"])

    @router.get(
        "/home",
        response_model=HomeResponse,
        responses=_ERROR_RESPONSES,
    )
    def get_home(
        response: Response,
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.tasks.get_today_home()

    @router.post(
        "/plans/today",
        response_model=EnsureTodayPlanResponse,
        responses=_ERROR_RESPONSES,
    )
    def ensure_today_plan(
        response: Response,
        idempotency_key: Annotated[
            str,
            Header(alias="Idempotency-Key", min_length=1, max_length=200),
        ],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.tasks.ensure_today_plan(
            idempotency_key=idempotency_key,
        )

    @router.get(
        "/plans/by-date/{plan_date}",
        response_model=HomeResponse,
        responses=_ERROR_RESPONSES,
    )
    def get_plan_day(
        response: Response,
        plan_date: date,
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.tasks.get_plan_day(plan_date)

    @router.post(
        "/plans/by-date/{plan_date}",
        response_model=EnsureTodayPlanResponse,
        responses=_ERROR_RESPONSES,
    )
    def ensure_plan_day(
        response: Response,
        plan_date: date,
        idempotency_key: Annotated[
            str,
            Header(alias="Idempotency-Key", min_length=1, max_length=200),
        ],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.tasks.ensure_plan_date(
            plan_date,
            idempotency_key=idempotency_key,
        )

    return router

def create_task_router(application_dependency: Callable[..., Any]) -> APIRouter:
    router = APIRouter(prefix="/v1", tags=["tasks"])

    @router.post("/tasks", response_model=TaskResponse, responses=_ERROR_RESPONSES)
    def create_task(
        request: TaskCreateRequest,
        response: Response,
        idempotency_key: Annotated[str, Header(alias="Idempotency-Key", min_length=1, max_length=200)],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.tasks.create_task(
            **request.model_dump(), idempotency_key=idempotency_key,
        )

    @router.get(
        "/tasks",
        response_model=TaskListResponse,
        responses=_ERROR_RESPONSES,
    )
    def list_tasks(
        limit: Annotated[int, Query(ge=1, le=100)] = 50,
        offset: Annotated[int, Query(ge=0, le=MAX_TASK_LIST_OFFSET)] = 0,
        application=Depends(application_dependency),
    ):
        return application.tasks.list_tasks(limit=limit, offset=offset)

    @router.patch(
        "/tasks/{task_id}",
        response_model=TaskResponse,
        responses=_ERROR_RESPONSES,
    )
    def update_task(
        request: TaskUpdateRequest,
        response: Response,
        task_id: Annotated[int, Path(gt=0, le=MAX_DATABASE_INTEGER)],
        idempotency_key: Annotated[
            str,
            Header(alias="Idempotency-Key", min_length=1, max_length=200),
        ],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.tasks.update_task(
            task_id,
            expected_version=request.expected_version,
            idempotency_key=idempotency_key,
            changes=request.changes(),
        )

    @router.post("/tasks/{task_id}/{command}", response_model=TaskResponse, responses=_ERROR_RESPONSES)
    def command_task(
        request: TaskCommandRequest,
        response: Response,
        task_id: Annotated[int, Path(gt=0, le=MAX_DATABASE_INTEGER)],
        command: Annotated[str, Path(pattern="^(start|pause|resume|complete|fail|defer)$")],
        idempotency_key: Annotated[str, Header(alias="Idempotency-Key", min_length=1, max_length=200)],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        kwargs = {"expected_version": request.expected_version, "idempotency_key": idempotency_key}
        if command == "fail":
            return application.tasks.fail(task_id, failure_reason=request.failure_reason or "", **kwargs)
        if command == "defer":
            return application.tasks.defer_to_tomorrow(task_id, **kwargs)
        return getattr(application.tasks, command)(task_id, **kwargs)

    @router.delete("/tasks/{task_id}", responses=_ERROR_RESPONSES)
    def delete_task(
        response: Response,
        task_id: Annotated[int, Path(gt=0, le=MAX_DATABASE_INTEGER)],
        expected_version: Annotated[int, Query(ge=0, le=MAX_DATABASE_INTEGER)],
        idempotency_key: Annotated[str, Header(alias="Idempotency-Key", min_length=1, max_length=200)],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.tasks.delete_task(
            task_id, expected_version=expected_version, idempotency_key=idempotency_key,
        )

    return router


def create_coach_router(application_dependency: Callable[..., Any]) -> APIRouter:
    router = APIRouter(prefix="/v1/coach", tags=["coach"])

    @router.get("/recommendations/today", response_model=CoachRecommendationResponse, responses=_ERROR_RESPONSES)
    def recommendations(response: Response, application=Depends(application_dependency)):
        _protect_private_response(response)
        return application.coaching.recommendations_for_today()

    @router.post("/chat", response_model=CoachChatResponse, responses=_ERROR_RESPONSES)
    def chat(
        request: CoachChatRequest,
        response: Response,
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        try:
            reply = application.coaching.chat(request.message)
        except ValueError as exc:
            from backend_errors import ConfigurationError
            raise ConfigurationError("The AI coach is not configured.") from exc
        return {"reply": reply}

    @router.post(
        "/voice/transcribe",
        response_model=VoiceTranscriptionResponse,
        responses=_ERROR_RESPONSES,
    )
    async def transcribe_voice(
        request: Request,
        response: Response,
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        mime_type = (request.headers.get("content-type") or "").split(";", 1)[0].strip().lower()
        if mime_type not in {"audio/mp4", "audio/m4a", "audio/aac", "audio/wav", "audio/mpeg"}:
            from backend_errors import ValidationError
            raise ValidationError("A supported audio content type is required.")
        content_length = request.headers.get("content-length")
        if content_length:
            from backend_errors import ValidationError
            try:
                declared_length = int(content_length)
            except ValueError as exc:
                raise ValidationError("The audio content length is invalid.") from exc
            if declared_length < 1 or declared_length > 15 * 1024 * 1024:
                raise ValidationError("The audio recording is empty or too large.")
        audio = await request.body()
        if not audio or len(audio) > 15 * 1024 * 1024:
            from backend_errors import ValidationError
            raise ValidationError("The audio recording is empty or too large.")
        transcript = application.coaching.transcribe_voice(audio, mime_type=mime_type)
        return {"transcript": transcript}

    return router


def create_planning_router(application_dependency: Callable[..., Any]) -> APIRouter:
    router = APIRouter(prefix="/v1/planning", tags=["planning"])

    @router.post("/draft", response_model=dict[str, Any], responses=_ERROR_RESPONSES)
    def draft(
        request: PlanningDraftRequest,
        response: Response,
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.planning.draft(request.raw_input)

    @router.post("/apply", response_model=dict[str, Any], responses=_ERROR_RESPONSES)
    def apply_draft(
        request: PlanningApplyRequest,
        response: Response,
        idempotency_key: Annotated[
            str,
            Header(alias="Idempotency-Key", min_length=1, max_length=200),
        ],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.planning.apply_draft(
            request.draft,
            raw_input=request.raw_input,
            idempotency_key=idempotency_key,
        )

    return router


def create_scheduling_router(application_dependency: Callable[..., Any]) -> APIRouter:
    router = APIRouter(prefix="/v1/scheduling", tags=["scheduling"])

    @router.post("/apply", response_model=dict[str, Any], responses=_ERROR_RESPONSES)
    def apply_schedule(
        request: SchedulePlanRequest,
        response: Response,
        idempotency_key: Annotated[
            str,
            Header(alias="Idempotency-Key", min_length=1, max_length=200),
        ],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.scheduling.schedule_plan(
            request.plan_id,
            expected_revision=request.expected_revision,
            work_day_start=time.fromisoformat(request.work_day_start),
            idempotency_key=idempotency_key,
        )

    @router.post(
        "/tasks/{task_id}/move",
        response_model=dict[str, Any],
        responses=_ERROR_RESPONSES,
    )
    def move_task(
        request: ScheduleMoveRequest,
        response: Response,
        task_id: Annotated[int, Path(gt=0, le=MAX_DATABASE_INTEGER)],
        idempotency_key: Annotated[
            str,
            Header(alias="Idempotency-Key", min_length=1, max_length=200),
        ],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.scheduling.move_task(
            task_id,
            expected_schedule_revision=request.expected_revision,
            new_start=time.fromisoformat(request.new_start),
            idempotency_key=idempotency_key,
        )

    @router.post(
        "/operations/{operation_id}/undo",
        response_model=dict[str, Any],
        responses=_ERROR_RESPONSES,
    )
    def undo_schedule(
        response: Response,
        operation_id: Annotated[str, Path(min_length=1, max_length=200)],
        idempotency_key: Annotated[
            str,
            Header(alias="Idempotency-Key", min_length=1, max_length=200),
        ],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.scheduling.undo(
            operation_id,
            idempotency_key=idempotency_key,
        )

    return router


def create_future_planning_router(application_dependency: Callable[..., Any]) -> APIRouter:
    router = APIRouter(prefix="/v1/future-planning", tags=["future-planning"])

    @router.get("/candidates", response_model=FuturePlanningCandidatesResponse, responses=_ERROR_RESPONSES)
    def candidates(
        response: Response,
        planning_start: date,
        planning_end: date,
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.scheduling.future_candidates(planning_start, planning_end)

    @router.post("/preview", response_model=FuturePlanningPreviewResponse, responses=_ERROR_RESPONSES)
    def preview(
        request: StructuredPlanningRequest,
        response: Response,
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.scheduling.public_future_preview(request)

    @router.post("/apply", response_model=FuturePlanningApplyResponse, responses=_ERROR_RESPONSES)
    def apply(
        request: StructuredPlanningRequest,
        response: Response,
        idempotency_key: Annotated[
            str,
            Header(alias="Idempotency-Key", min_length=1, max_length=200),
        ],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.scheduling.preview_and_apply_future(
            request, idempotency_key=idempotency_key,
        )

    return router

def create_analytics_router(application_dependency: Callable[..., Any]) -> APIRouter:
    """Expose the complete principal-scoped analytics profile used by mobile."""

    router = APIRouter(prefix="/v1", tags=["analytics"])

    @router.get(
        "/analytics",
        response_model=dict[str, Any],
        responses=_ERROR_RESPONSES,
    )
    def analytics_profile(
        response: Response,
        window_days: Annotated[int, Query(ge=1, le=365)] = 30,
        language: Annotated[str, Query(pattern="^(ar|en)$")] = "en",
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.analytics.profile(
            window_days=window_days,
            language=language,
        )

    return router


def create_profile_router(application_dependency: Callable[..., Any]) -> APIRouter:
    router = APIRouter(prefix="/v1/profile", tags=["profile"])

    @router.get(
        "",
        response_model=ProfileResponse,
        responses=_ERROR_RESPONSES,
    )
    def get_profile(
        response: Response,
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.account.get_profile()

    @router.patch(
        "/display-name",
        response_model=ProfileResponse,
        responses=_ERROR_RESPONSES,
    )
    def update_display_name(
        request: ProfileDisplayNameUpdateRequest,
        response: Response,
        idempotency_key: Annotated[
            str,
            Header(alias="Idempotency-Key", min_length=1, max_length=200),
        ],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.account.change_display_name(
            request.display_name,
            idempotency_key=idempotency_key,
        )

    @router.patch(
        "/timezone",
        response_model=ProfileTimezoneUpdateResponse,
        responses=_ERROR_RESPONSES,
    )
    def update_timezone(
        request: ProfileTimezoneUpdateRequest,
        response: Response,
        idempotency_key: Annotated[
            str,
            Header(alias="Idempotency-Key", min_length=1, max_length=200),
        ],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.account.change_timezone(
            request.timezone,
            idempotency_key=idempotency_key,
        )

    return router


def create_notification_router(application_dependency: Callable[..., Any]) -> APIRouter:
    router = APIRouter(prefix="/v1/notifications", tags=["notifications"])

    @router.post(
        "/devices",
        response_model=NotificationDeviceResponse,
        responses=_ERROR_RESPONSES,
    )
    def register_device(
        request: NotificationDeviceRegistrationRequest,
        response: Response,
        idempotency_key: Annotated[
            str,
            Header(alias="Idempotency-Key", min_length=1, max_length=200),
        ],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.notifications.register_device(
            device_id=request.device_id,
            platform="android",
            provider="fcm",
            push_token=request.push_token,
            app_identifier=request.app_identifier,
            app_version=request.app_version,
            locale=request.locale,
            timezone_name=request.timezone,
            is_primary=True,
            is_enabled=True,
            idempotency_key=idempotency_key,
        )

    @router.delete(
        "/devices/{device_id}",
        response_model=NotificationDeviceInvalidationResponse,
        responses=_ERROR_RESPONSES,
    )
    def invalidate_device(
        response: Response,
        device_id: Annotated[str, Path(min_length=1, max_length=200)],
        idempotency_key: Annotated[
            str,
            Header(alias="Idempotency-Key", min_length=1, max_length=200),
        ],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.notifications.invalidate_device(
            device_id,
            idempotency_key=idempotency_key,
        )

    @router.get(
        "/preferences",
        response_model=NotificationPreferencesResponse,
        responses=_ERROR_RESPONSES,
    )
    def get_preferences(
        response: Response,
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.notifications.get_preferences()

    @router.patch(
        "/preferences",
        response_model=NotificationPreferencesResponse,
        responses=_ERROR_RESPONSES,
    )
    def update_preferences(
        request: NotificationPreferencesUpdateRequest,
        response: Response,
        idempotency_key: Annotated[
            str,
            Header(alias="Idempotency-Key", min_length=1, max_length=200),
        ],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.notifications.update_preferences(
            idempotency_key=idempotency_key,
            changes=request.changes(),
        )

    return router


def create_calendar_router(application_dependency: Callable[..., Any]) -> APIRouter:
    router = APIRouter(prefix="/v1/calendar", tags=["calendar"])

    @router.get("/oauth/callback", include_in_schema=False)
    def oauth_callback(
        code: Annotated[str | None, Query(max_length=4096)] = None,
        state: Annotated[str | None, Query(max_length=1024)] = None,
        error: Annotated[str | None, Query(max_length=200)] = None,
    ):
        if error:
            query = urlencode({"error": "authorization_denied"})
        elif code and state:
            query = urlencode({"code": code, "state": state})
        else:
            query = urlencode({"error": "invalid_callback"})
        response = RedirectResponse(f"coachai://calendar/oauth?{query}", status_code=302)
        _protect_private_response(response)
        return response

    @router.get("", response_model=CalendarConnectionResponse, responses=_ERROR_RESPONSES)
    def get_connection(response: Response, application=Depends(application_dependency)):
        _protect_private_response(response)
        return application.calendar.overview()

    @router.post("/oauth/start", response_model=CalendarAuthorizationResponse, responses=_ERROR_RESPONSES)
    def start_authorization(response: Response, application=Depends(application_dependency)):
        _protect_private_response(response)
        return application.calendar.begin_authorization()

    @router.post("/oauth/complete", response_model=CalendarConnectionResponse, responses=_ERROR_RESPONSES)
    def complete_authorization(
        request: CalendarAuthorizationCompleteRequest,
        response: Response,
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        application.calendar.complete_authorization(code=request.code, state=request.state)
        return application.calendar.overview()

    @router.get("/calendars", response_model=list[CalendarItemResponse], responses=_ERROR_RESPONSES)
    def list_calendars(response: Response, application=Depends(application_dependency)):
        _protect_private_response(response)
        return application.calendar.list_available_calendars()

    @router.patch("/calendars", response_model=CalendarConnectionResponse, responses=_ERROR_RESPONSES)
    def select_calendars(
        request: CalendarSelectionRequest,
        response: Response,
        idempotency_key: Annotated[str, Header(alias="Idempotency-Key", min_length=1, max_length=200)],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        application.calendar.set_selected_calendars(
            [item.model_dump(exclude={"selected"}) | {"calendar_name": item.name} for item in request.calendars],
            idempotency_key=idempotency_key,
        )
        return application.calendar.overview()

    @router.post("/sync", responses=_ERROR_RESPONSES)
    def request_sync(
        request: CalendarSyncRequest,
        response: Response,
        idempotency_key: Annotated[str, Header(alias="Idempotency-Key", min_length=1, max_length=200)],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        return application.calendar.request_sync(
            range_start=request.range_start,
            range_end=request.range_end,
            idempotency_key=idempotency_key,
        )

    @router.post("/disconnect", response_model=CalendarConnectionResponse, responses=_ERROR_RESPONSES)
    def disconnect(
        response: Response,
        idempotency_key: Annotated[str, Header(alias="Idempotency-Key", min_length=1, max_length=200)],
        application=Depends(application_dependency),
    ):
        _protect_private_response(response)
        application.calendar.disconnect(idempotency_key=idempotency_key)
        return application.calendar.overview()

    return router
