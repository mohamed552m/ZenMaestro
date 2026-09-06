from datetime import date
from typing import Annotated
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.dependencies.auth import AuthenticatedUser, get_current_user
from app.db.session import get_db_session
from app.models.plan import Plan
from app.models.reflection import Reflection
from app.models.task import Task
from app.schemas.reflection import ReflectionResponse, ReflectionUpsert


router = APIRouter(tags=["reflections"])
DatabaseSession = Annotated[Session, Depends(get_db_session)]
CurrentUser = Annotated[AuthenticatedUser, Depends(get_current_user)]


def _owned_task(session: Session, user_uid: str, task_id: str) -> Task:
    task = session.scalar(
        select(Task)
        .join(Plan)
        .where(Task.id == task_id, Plan.user_uid == user_uid)
    )
    if task is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Task not found.")
    return task


@router.put("/tasks/{task_id}/reflection", response_model=ReflectionResponse)
def upsert_reflection(
    task_id: str,
    payload: ReflectionUpsert,
    session: DatabaseSession,
    user: CurrentUser,
) -> Reflection:
    _owned_task(session, user.uid, task_id)
    reflection = session.scalar(
        select(Reflection).where(
            Reflection.task_id == task_id,
            Reflection.user_uid == user.uid,
        )
    )
    if reflection is None:
        reflection = Reflection(
            id=str(uuid4()),
            user_uid=user.uid,
            task_id=task_id,
            effort=payload.effort,
            note=payload.note,
            completed_at=payload.completed_at,
        )
        session.add(reflection)
    else:
        reflection.effort = payload.effort
        reflection.note = payload.note
        reflection.completed_at = payload.completed_at
    session.commit()
    session.refresh(reflection)
    return reflection


@router.get("/reflections", response_model=list[ReflectionResponse])
def list_reflections(
    session: DatabaseSession,
    user: CurrentUser,
    start_date: Annotated[date | None, Query()] = None,
    end_date: Annotated[date | None, Query()] = None,
) -> list[Reflection]:
    if start_date is not None and end_date is not None and start_date > end_date:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="start_date must be on or before end_date.",
        )
    query = (
        select(Reflection)
        .join(Task)
        .join(Plan)
        .where(Reflection.user_uid == user.uid, Plan.user_uid == user.uid)
        .order_by(Reflection.completed_at.desc())
    )
    if start_date is not None:
        query = query.where(Plan.plan_date >= start_date)
    if end_date is not None:
        query = query.where(Plan.plan_date <= end_date)
    return list(session.scalars(query).all())
