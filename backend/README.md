# CoachAI FastAPI backend

This service exposes the CoachAI domain logic to the ZenMaestro Android app.

## Current scope

- Versioned API under `/api/v1`
- Health endpoint at `/api/v1/health`
- Firebase-protected account endpoint at `/api/v1/account/me`
- Profile preferences at `/api/v1/account/profile` and `/api/v1/account/preferences`
- Learner-controlled data deletion at `/api/v1/account/learning-data`
- Firebase-protected daily plan and task CRUD endpoints
- Idempotent task and reflection synchronization for offline Android changes
- Progress summary generated from saved task history
- Secure Gemini coach and plan-draft endpoints (enabled only when the server key exists)
- Environment-based settings with no committed secrets
- PostgreSQL-ready SQLAlchemy models and Alembic migrations
- A clean boundary for refactoring planner, scheduler, analytics, recommendations, and coach services away from Streamlit

The existing Streamlit application remains available during the migration. The committed `coach_ai.db` file is not used as a production database. Firebase remains the identity provider, so this API will verify Firebase ID tokens and will never store user passwords.

Set `ZENMAESTRO_DATABASE_URL` to a PostgreSQL URL such as `postgresql+psycopg://user:password@host/database`. Apply schema changes with `.\.venv\Scripts\python.exe -m alembic upgrade head`.

Copy `.env.example` to `.env` for local development. Keep the Firebase service-account JSON and Gemini key outside Git. The required production values are:

- `ZENMAESTRO_ENVIRONMENT=production`
- `ZENMAESTRO_FIREBASE_PROJECT_ID=zenmaestro-9b44c`
- `ZENMAESTRO_FIREBASE_CREDENTIALS_PATH=/run/secrets/firebase-service-account.json`
- `ZENMAESTRO_REQUIRE_APP_CHECK=true`
- `ZENMAESTRO_DATABASE_URL=postgresql+psycopg://...`
- `ZENMAESTRO_GEMINI_API_KEY=...` (may stay empty until AI is enabled)
- `ZENMAESTRO_GEMINI_MODEL=gemini-3.6-flash`
- `ZENMAESTRO_GEMINI_FALLBACK_MODELS=gemini-3.5-flash,gemini-2.5-flash`

The service automatically tries the fallback models when the selected Gemini
model is temporarily overloaded, rate-limited, or no longer available.

## Local run

From this `backend` directory:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload
```

The health endpoint is available at `http://127.0.0.1:8000/api/v1/health`.
The readiness endpoint at `http://127.0.0.1:8000/api/v1/ready` also checks the database.
Interactive API documentation is available at `http://127.0.0.1:8000/api/v1/docs`.

Every endpoint that reads or changes learner data expects a Firebase ID token in
the `Authorization: Bearer <token>` header. The backend derives the learner ID
from that verified token; it never accepts a caller-provided user ID.
Production should set `ZENMAESTRO_REQUIRE_APP_CHECK=true`; Android sends its Firebase App Check token in `X-Firebase-AppCheck` with every backend request. Keep it `false` only for local API testing that does not run through the Android app.

The coach endpoint receives only the message and chat history explicitly sent by the Android client. It does not automatically export saved tasks, progress, reflections, the learner's name, or email to Gemini. The plan-draft endpoint returns a draft for review and never saves or changes tasks by itself.

## Container deployment

Build from this directory with `docker build -t zenmaestro-api .`. The container runs all pending database migrations before starting FastAPI. Mount the Firebase service-account file as a secret and provide the environment values above through the hosting platform's secret manager.

## Tests

```powershell
.\.venv\Scripts\python.exe -m pytest
```
