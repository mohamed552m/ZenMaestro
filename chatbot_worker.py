"""Isolated provider worker for one bounded CoachAI chat request."""

from __future__ import annotations

import json
import logging
import sys

from chatbot_service import ChatbotService
from domain_validation import MAX_AI_INPUT_CHARS


def main() -> int:
    logging.basicConfig(
        level=logging.WARNING,
        stream=sys.stderr,
        format="chatbot_worker %(levelname)s %(name)s: %(message)s",
    )
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        payload = json.loads(sys.stdin.read(MAX_AI_INPUT_CHARS + 4096))
        user_id = str(payload["user_id"]).strip()
        message = str(payload["message"])
        if not user_id or not message.strip() or len(message) > MAX_AI_INPUT_CHARS:
            return 2
        reply = ChatbotService(user_id).send_message(message)
        sys.stdout.write(json.dumps({"reply": reply}, ensure_ascii=False))
        return 0
    except Exception as exc:
        logging.getLogger("coachai.chat_worker").warning(
            "chat_worker_failed error_type=%s", type(exc).__name__
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
