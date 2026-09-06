# ZenMaestro

ZenMaestro is an AI learning coach built with Kotlin, XML, Android Views,
Material Components, Firebase Authentication/App Check, FastAPI, and Gemini.

This repository contains the complete development workspace:

- `app/`: Android application.
- `backend/`: FastAPI API, database models, migrations, and tests.

Secrets are intentionally not committed. Do not commit `.env`, Firebase Admin
service-account files, Gemini API keys, local databases, `local.properties`, or
`app/google-services.json`.

## 1. Required local files

Ask a Firebase project owner for access, then place the Android Firebase config
at:

```text
app/google-services.json
```

Create `backend/.env` from `backend/.env.example` and set the local values:

```env
ZENMAESTRO_ENVIRONMENT=development
ZENMAESTRO_FIREBASE_PROJECT_ID=zenmaestro-9b44c
ZENMAESTRO_FIREBASE_CREDENTIALS_PATH=D:/your-private-folder/firebase-admin.json
ZENMAESTRO_REQUIRE_APP_CHECK=false
ZENMAESTRO_DATABASE_URL=sqlite+pysqlite:///./zenmaestro-dev.db
ZENMAESTRO_GEMINI_API_KEY=replace_with_your_gemini_key
ZENMAESTRO_GEMINI_MODEL=gemini-3.6-flash
ZENMAESTRO_GEMINI_FALLBACK_MODELS=gemini-3.5-flash,gemini-2.5-flash
```

Share these private values with team members through a password manager or
another private channel, never through GitHub or chat screenshots.

## 2. Run the backend on Windows

From the repository root:

```powershell
cd backend
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m alembic upgrade head
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Verify these URLs:

- `http://127.0.0.1:8000/api/v1/health`
- `http://127.0.0.1:8000/api/v1/ready`
- `http://127.0.0.1:8000/api/v1/docs`

Run the backend tests with:

```powershell
.\.venv\Scripts\python.exe -m pytest -q
```

## 3. Configure and run Android

Open the repository root in Android Studio. Android Studio creates the SDK entry
inside `local.properties`. Add this line to the same file for local backend
development:

```properties
ZENMAESTRO_API_BASE_URL=http://127.0.0.1:8000
```

Connect the phone or start an emulator, then forward the backend port:

```powershell
adb reverse tcp:8000 tcp:8000
```

Build and run the `app` configuration. Debug builds use Firebase App Check's
debug provider. Each developer must copy their debug token from Logcat and ask a
Firebase project owner to register it under App Check > Apps > Manage debug
tokens.

## 4. Team workflow

Create a separate branch for each task:

```powershell
git switch -c feature/short-task-name
git add <changed-files>
git commit -m "describe the change"
git push -u origin feature/short-task-name
```

Open a pull request into `main`. Never push keys or local configuration files.

More backend details are available in `backend/README.md`.
