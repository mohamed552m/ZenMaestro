import json
import mimetypes
from datetime import datetime, timedelta, timezone
from typing import Annotated
from uuid import uuid4

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from pydantic import TypeAdapter, ValidationError
from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from app.api.dependencies.auth import AuthenticatedUser, get_current_user
from app.db.session import get_db_session
from app.models.chat import ChatConversation, ChatMessage
from app.schemas.ai import (
    AiStatusResponse,
    ChatConversationResponse,
    CoachChatRequest,
    CoachChatResponse,
    ChatTurn,
    PlanDraftRequest,
    PlanDraftResponse,
)
from app.services.gemini import (
    AiNotConfiguredError,
    AiProviderError,
    ChatAttachment,
    GeminiService,
    get_gemini_service,
)
from app.services.accounts import ensure_database_user


router = APIRouter(tags=["ai"])
CurrentUser = Annotated[AuthenticatedUser, Depends(get_current_user)]
AiService = Annotated[GeminiService, Depends(get_gemini_service)]
DatabaseSession = Annotated[Session, Depends(get_db_session)]

MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024
ALLOWED_ATTACHMENT_MIME_TYPES = {
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/gif",
    "image/heic",
    "image/heif",
    "application/pdf",
    "application/json",
    "text/plain",
    "text/markdown",
    "text/csv",
}


def _translate_ai_error(error: Exception) -> HTTPException:
    if isinstance(error, AiNotConfiguredError):
        return HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="AI is not configured on the server yet.",
        )
    return HTTPException(
        status_code=status.HTTP_502_BAD_GATEWAY,
        detail="AI provider is temporarily unavailable.",
    )


@router.get("/ai/status", response_model=AiStatusResponse)
def read_ai_status(user: CurrentUser, service: AiService) -> AiStatusResponse:
    return AiStatusResponse(configured=service.configured, model=service.model)


@router.post("/coach/chat", response_model=CoachChatResponse)
def coach_chat(
    payload: CoachChatRequest,
    user: CurrentUser,
    service: AiService,
    session: DatabaseSession,
) -> CoachChatResponse:
    conversation = _resolve_conversation(
        session=session,
        user=user,
        conversation_id=payload.conversation_id,
        title_source=payload.message,
    )
    try:
        reply = service.chat(payload.message, payload.history)
    except (AiNotConfiguredError, AiProviderError) as error:
        raise _translate_ai_error(error) from error
    _save_exchange(
        session=session,
        conversation=conversation,
        message=payload.message,
        reply=reply,
    )
    return CoachChatResponse(reply=reply, conversation_id=conversation.id)


@router.post("/coach/chat/attachment", response_model=CoachChatResponse)
async def coach_chat_with_attachment(
    user: CurrentUser,
    service: AiService,
    session: DatabaseSession,
    message: Annotated[str, Form(min_length=1, max_length=4000)],
    history_json: Annotated[str, Form()] = "[]",
    conversation_id: Annotated[str | None, Form()] = None,
    attachment: UploadFile = File(...),
) -> CoachChatResponse:
    normalized_message = message.strip()
    if not normalized_message:
        raise HTTPException(status_code=422, detail="Message cannot be blank.")
    try:
        parsed_history = TypeAdapter(list[ChatTurn]).validate_python(json.loads(history_json))
    except (json.JSONDecodeError, ValidationError) as error:
        raise HTTPException(status_code=422, detail="Chat history is invalid.") from error
    if len(parsed_history) > 20:
        raise HTTPException(status_code=422, detail="Chat history cannot exceed 20 turns.")

    filename = (attachment.filename or "attachment").strip()[:255]
    guessed_mime = mimetypes.guess_type(filename)[0]
    mime_type = (attachment.content_type or guessed_mime or "").lower()
    if mime_type not in ALLOWED_ATTACHMENT_MIME_TYPES:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail="Choose an image, PDF, or UTF-8 text file.",
        )
    data = await attachment.read(MAX_ATTACHMENT_BYTES + 1)
    await attachment.close()
    if not data:
        raise HTTPException(status_code=422, detail="The selected file is empty.")
    if len(data) > MAX_ATTACHMENT_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail="The selected file must be 10 MB or smaller.",
        )

    selected_attachment = ChatAttachment(filename=filename, mime_type=mime_type, data=data)
    conversation = _resolve_conversation(
        session=session,
        user=user,
        conversation_id=conversation_id,
        title_source=normalized_message,
    )
    try:
        reply = service.chat(normalized_message, parsed_history, selected_attachment)
    except (AiNotConfiguredError, AiProviderError) as error:
        raise _translate_ai_error(error) from error
    _save_exchange(
        session=session,
        conversation=conversation,
        message=normalized_message,
        reply=reply,
        attachment_name=filename,
        attachment_mime=mime_type,
    )
    return CoachChatResponse(reply=reply, conversation_id=conversation.id)


@router.get("/coach/conversations/latest", response_model=ChatConversationResponse | None)
def read_latest_conversation(
    user: CurrentUser,
    session: DatabaseSession,
) -> ChatConversation | None:
    return session.scalar(
        select(ChatConversation)
        .where(ChatConversation.user_uid == user.uid)
        .options(selectinload(ChatConversation.messages))
        .order_by(ChatConversation.updated_at.desc())
        .limit(1)
    )


def _resolve_conversation(
    *,
    session: Session,
    user: AuthenticatedUser,
    conversation_id: str | None,
    title_source: str,
) -> ChatConversation:
    ensure_database_user(session, user)
    conversation = None
    if conversation_id:
        conversation = session.scalar(
            select(ChatConversation).where(
                ChatConversation.id == conversation_id,
                ChatConversation.user_uid == user.uid,
            )
        )
        if conversation is None:
            raise HTTPException(status_code=404, detail="Conversation not found.")
    if conversation is None:
        conversation = ChatConversation(
            id=str(uuid4()),
            user_uid=user.uid,
            title=title_source[:120],
        )
        session.add(conversation)
        session.flush()
    return conversation


def _save_exchange(
    *,
    session: Session,
    conversation: ChatConversation,
    message: str,
    reply: str,
    attachment_name: str | None = None,
    attachment_mime: str | None = None,
) -> None:
    exchange_time = datetime.now(timezone.utc)
    session.add_all(
        [
            ChatMessage(
                id=str(uuid4()),
                conversation_id=conversation.id,
                role="user",
                content=message,
                attachment_name=attachment_name,
                attachment_mime=attachment_mime,
                created_at=exchange_time,
                updated_at=exchange_time,
            ),
            ChatMessage(
                id=str(uuid4()),
                conversation_id=conversation.id,
                role="model",
                content=reply,
                created_at=exchange_time + timedelta(microseconds=1),
                updated_at=exchange_time + timedelta(microseconds=1),
            ),
        ]
    )
    conversation.updated_at = exchange_time
    session.commit()


@router.post("/planner/draft", response_model=PlanDraftResponse)
def draft_plan(
    payload: PlanDraftRequest,
    user: CurrentUser,
    service: AiService,
) -> PlanDraftResponse:
    try:
        return service.draft_plan(payload.raw_input, payload.existing_categories)
    except (AiNotConfiguredError, AiProviderError) as error:
        raise _translate_ai_error(error) from error
