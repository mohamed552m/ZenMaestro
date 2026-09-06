from datetime import date, datetime, timezone
from typing import Annotated
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from app.api.dependencies.auth import AuthenticatedUser, get_current_user
from app.db.session import get_db_session
from app.models.plan import Plan
from app.models.task import Task
from app.schemas.plan import PlanResponse, PlanUpsert
from app.schemas.task import TaskCreate, TaskResponse, TaskSnapshot, TaskUpdate
from app.services.accounts import ensure_database_user


router = APIRouter(tags=["plans"])
DatabaseSession = Annotated[Session, Depends(get_db_session)]
CurrentUser = Annotated[AuthenticatedUser, Depends(get_current_user)]


def _owned_plan_query(user_uid: str, plan_date: date):
    return (
        select(Plan)
        .where(Plan.user_uid == user_uid, Plan.plan_date == plan_date)
        .options(selectinload(Plan.tasks))
    )


def _get_owned_task(session: Session, user_uid: str, task_id: str) -> Task:
    task = session.scalar(
        select(Task)
        .join(Plan)
        .where(Task.id == task_id, Plan.user_uid == user_uid)
    )
    if task is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Task not found.")
    return task


@router.get("/plans", response_model=list[PlanResponse])
def list_plans(
    session: DatabaseSession,
    user: CurrentUser,
    start_date: Annotated[date | None, Query()] = None,
    end_date: Annotated[date | None, Query()] = None,
) -> list[Plan]:
    if start_date is not None and end_date is not None and start_date > end_date:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="start_date must be on or before end_date.",
        )
    query = (
        select(Plan)
        .where(Plan.user_uid == user.uid)
        .options(selectinload(Plan.tasks))
        .order_by(Plan.plan_date.desc())
    )
    if start_date is not None:
        query = query.where(Plan.plan_date >= start_date)
    if end_date is not None:
        query = query.where(Plan.plan_date <= end_date)
    return list(session.scalars(query).all())


@router.get("/plans/{plan_date}", response_model=PlanResponse)
def read_plan(plan_date: date, session: DatabaseSession, user: CurrentUser) -> Plan:
    plan = session.scalar(_owned_plan_query(user.uid, plan_date))
    if plan is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Plan not found.")
    return plan


@router.put("/plans/{plan_date}", response_model=PlanResponse)
def upsert_plan(
    plan_date: date,
    payload: PlanUpsert,
    session: DatabaseSession,
    user: CurrentUser,
) -> Plan:
    ensure_database_user(session, user)
    plan = session.scalar(_owned_plan_query(user.uid, plan_date))
    if plan is None:
        plan = Plan(id=str(uuid4()), user_uid=user.uid, plan_date=plan_date)
        session.add(plan)
    plan.raw_input = payload.raw_input.strip() or None if payload.raw_input is not None else None
    plan.ai_summary = payload.ai_summary.strip() or None if payload.ai_summary is not None else None
    plan.status = payload.status
    session.commit()
    refreshed = session.scalar(_owned_plan_query(user.uid, plan_date))
    if refreshed is None:
        raise RuntimeError("Plan could not be loaded after saving.")
    return refreshed


@router.post(
    "/plans/{plan_date}/tasks",
    response_model=TaskResponse,
    status_code=status.HTTP_201_CREATED,
)
def create_task(
    plan_date: date,
    payload: TaskCreate,
    session: DatabaseSession,
    user: CurrentUser,
) -> Task:
    ensure_database_user(session, user)
    plan = session.scalar(_owned_plan_query(user.uid, plan_date))
    if plan is None:
        plan = Plan(
            id=str(uuid4()),
            user_uid=user.uid,
            plan_date=plan_date,
            raw_input="Added manually by the learner.",
        )
        session.add(plan)
        session.flush()

    task_id = str(payload.id or uuid4())
    if session.get(Task, task_id) is not None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Task ID already exists.")

    task = Task(
        id=task_id,
        plan_id=plan.id,
        title=payload.title,
        description=payload.description,
        task_type=payload.task_type,
        priority=payload.priority,
        estimated_minutes=payload.estimated_minutes,
        scheduled_start=payload.scheduled_start,
        scheduled_end=payload.scheduled_end,
        is_fixed_time=payload.is_fixed_time,
        is_break=payload.is_break,
        order_index=payload.order_index,
    )
    session.add(task)
    session.commit()
    session.refresh(task)
    return task


@router.put("/plans/{plan_date}/tasks/{task_id}", response_model=TaskResponse)
def put_task_snapshot(
    plan_date: date,
    task_id: str,
    payload: TaskSnapshot,
    session: DatabaseSession,
    user: CurrentUser,
) -> Task:
    ensure_database_user(session, user)
    plan = session.scalar(_owned_plan_query(user.uid, plan_date))
    if plan is None:
        plan = Plan(
            id=str(uuid4()),
            user_uid=user.uid,
            plan_date=plan_date,
            raw_input="Added manually by the learner.",
        )
        session.add(plan)
        session.flush()

    task = session.get(Task, task_id)
    if task is not None and task.plan.user_uid != user.uid:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Task not found.")
    if task is None:
        task = Task(id=task_id, plan_id=plan.id, title=payload.title)
        session.add(task)

    task.plan_id = plan.id
    task.title = payload.title
    task.description = payload.description
    task.task_type = payload.task_type
    task.priority = payload.priority
    task.estimated_minutes = payload.estimated_minutes
    task.scheduled_start = payload.scheduled_start
    task.scheduled_end = payload.scheduled_end
    task.is_fixed_time = payload.is_fixed_time
    task.is_break = payload.is_break
    task.order_index = payload.order_index
    task.status = payload.status
    task.actual_minutes = payload.actual_minutes
    if payload.status == "completed" and task.completed_at is None:
        task.completed_at = datetime.now(timezone.utc)
    elif payload.status != "completed":
        task.completed_at = None
    session.commit()
    session.refresh(task)
    return task


@router.patch("/tasks/{task_id}", response_model=TaskResponse)
def update_task(
    task_id: str,
    payload: TaskUpdate,
    session: DatabaseSession,
    user: CurrentUser,
) -> Task:
    task = _get_owned_task(session, user.uid, task_id)
    updates = payload.model_dump(exclude_unset=True)
    if not updates:
        return task

    if updates.get("status") == "in_progress" and task.started_at is None:
        task.started_at = datetime.now(timezone.utc)
    if updates.get("status") == "completed":
        task.completed_at = datetime.now(timezone.utc)
    elif "status" in updates and task.status == "completed":
        task.completed_at = None

    for field, value in updates.items():
        setattr(task, field, value)
    if task.is_break:
        task.is_fixed_time = True
    session.commit()
    session.refresh(task)
    return task


@router.delete("/tasks/{task_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_task(
    task_id: str,
    session: DatabaseSession,
    user: CurrentUser,
) -> Response:
    task = _get_owned_task(session, user.uid, task_id)
    session.delete(task)
    session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)
