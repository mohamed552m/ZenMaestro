from datetime import date, timedelta
from typing import Annotated

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.dependencies.auth import AuthenticatedUser, get_current_user
from app.db.session import get_db_session
from app.models.plan import Plan
from app.models.task import Task
from app.schemas.progress import ProgressSummary


router = APIRouter(prefix="/progress", tags=["progress"])
DatabaseSession = Annotated[Session, Depends(get_db_session)]
CurrentUser = Annotated[AuthenticatedUser, Depends(get_current_user)]


@router.get("/summary", response_model=ProgressSummary)
def read_progress_summary(
    session: DatabaseSession,
    user: CurrentUser,
    as_of: Annotated[date, Query()] = date.today(),
) -> ProgressSummary:
    rows = session.execute(
        select(Task, Plan.plan_date)
        .join(Plan)
        .where(Plan.user_uid == user.uid, Task.is_break.is_(False))
    ).all()
    completed_rows = [(task, plan_date) for task, plan_date in rows if task.status == "completed"]
    completed_days = {plan_date for _, plan_date in completed_rows}

    streak_cursor = as_of
    if streak_cursor not in completed_days:
        streak_cursor -= timedelta(days=1)
    current_streak = 0
    while streak_cursor in completed_days:
        current_streak += 1
        streak_cursor -= timedelta(days=1)

    week_start = as_of - timedelta(days=as_of.weekday())
    week_end = week_start + timedelta(days=6)
    week_rows = [
        (task, plan_date)
        for task, plan_date in rows
        if week_start <= plan_date <= week_end
    ]
    week_completed = sum(1 for task, _ in week_rows if task.status == "completed")
    week_percent = (week_completed * 100 // len(week_rows)) if week_rows else 0

    return ProgressSummary(
        as_of=as_of,
        total_tasks=len(rows),
        completed_tasks=len(completed_rows),
        focused_minutes=sum(
            task.actual_minutes if task.actual_minutes is not None else task.estimated_minutes
            for task, _ in completed_rows
        ),
        current_streak_days=current_streak,
        week_start=week_start,
        week_end=week_end,
        week_tasks=len(week_rows),
        week_completed_tasks=week_completed,
        week_completion_percent=week_percent,
    )
