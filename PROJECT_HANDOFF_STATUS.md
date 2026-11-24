# Zen Maestro — Project Handoff Status

Last updated: 2026-11-08 (Africa/Cairo)

## 1. Current source of truth

- GitHub: `https://github.com/YousefElmasry1/CoachAI`
- Branch: `main`
- Main delivery commit: `b0cde47886272d6dd9a96d22ae1e7a94446cf93d`
- Canonical local copy: `D:\CoachAI`
- Android application ID: `com.zenmaestro.app`
- App name: **Zen Maestro**
- Latest debug APK: `D:\CoachAI\android\app\build\outputs\apk\debug\app-debug.apk`

The APK built from `D:\CoachAI` was installed successfully on the connected phone. A clean Android build was also run from D with a fresh Gradle cache and `--rerun-tasks`; all 45 build/test tasks completed successfully.

## 2. What is implemented

### Authentication and account

- Firebase email/password sign-in and account creation UI.
- Google sign-in UI and Firebase token provider.
- Forgot-password flow and password reset deep-link handling.
- Registration waits for profile provisioning before entering the app.
- User-scoped ViewModels prevent account data leaking between sign-ins.
- Profile displays the real display name, email, join date, masked account ID, and timezone.
- The display name is used inside the app.

### Home

- Server-backed Home state with daily plan, progress, tasks, and personalized name.
- Product-facing empty/error copy; old Android/debug/support labels were removed.
- Light/dark theme support.
- Refreshes from the server when returning to Home.

### Schedule and tasks

- Day strip with server-backed plan loading.
- A selected future day creates/saves tasks under that exact date.
- Dates before today cannot be selected for planning.
- Backend rejects create/update/delete/lifecycle/move/schedule operations on past plans.
- Manual, AI, voice, and break entry points.
- `+ Add another task` is shown directly below the task list.
- Temporary local task IDs are replaced correctly by durable server IDs.
- Start, Pause, Resume, Complete, Fail, More/Move, Defer, and Undo are wired to the backend.
- Day-start control is labeled **Schedule settings** / **Your day starts at**.
- After adding a task: reload revision → reschedule pending flexible work → reload the complete day.
- Running, completed, and fixed-time work is protected during rescheduling.
- The UI reloads all server tasks so occupied slots are visible instead of making a later start look unexplained.
- Smart Schedule, manual Move, and scheduling Undo now reload the selected day from the backend after a successful write; late reloads cannot overwrite a newly selected day.
- Smart Schedule, manual Move, and scheduling Undo share an in-flight write guard so repeated taps cannot submit overlapping schedule mutations.

### Focus timer and notifications

- Real Android foreground focus-timer service.
- Ongoing silent notification shows task name and remaining time.
- Timer continues in the background, updates on pause/resume, and stops on completion.
- Android notification permission is requested when needed.
- Notification preferences are server-backed.
- Sound/vibration choices control separate Android notification channels.
- 12/24-hour formatting works in quiet-hours controls and time pickers.
- FCM device registration uses the real Firebase Messaging token.
- Backend FCM delivery is data-only so Android channel preferences remain authoritative.
- PostgreSQL device registration serializes duplicate device/token identities with transaction-level advisory locks, preventing parallel registration races without weakening tenant ownership checks.
- Notification taps route to the relevant app section.

### Future Planning

- Dedicated Future Planning screen.
- Select a date range or one day, work start, saved tasks, and new draft work.
- Duration, priority, earliest start, and deadline support.
- Preview distribution before saving.
- Apply and undo are server-backed and idempotent.
- Past dates are rejected in UI and backend.
- Tested on device: add draft → preview → apply → confirm → undo.

### Coach and Ask

- Coach report and interactive Ask Coach are separate features.
- Authenticated backend chat endpoint with bounded input/output.
- Double-send is prevented while a request is active.
- Mobile connect/read timeouts reduced to 8s/35s.
- Gemini retries reduced to 2; tool rounds default to 2 (maximum 3); chat output defaults to 1024 tokens.
- Clear progress and safe fallback messages are shown.

### Analytics

- Server-backed Analytics payload.
- 7/30/90-day filters issue distinct API requests.
- Same-period tap refreshes.
- Stale responses cannot overwrite a newer selection.
- Loading/error states keep the selected window.
- UI displays `Showing the last X days`.

### More, settings, profile, calendar

- More screen links to Coach Report, Profile, Notifications, Calendar, and Settings.
- Back navigation uses an internal stack instead of exiting the whole app from detail screens.
- Theme, time format, haptics, and reduce-motion settings are implemented.
- Calendar connection UI and backend OAuth contract are implemented.

## 3. Backend and API work

- FastAPI application with authenticated, principal-scoped routes.
- Home, plans by date, tasks, lifecycle commands, scheduling, Future Planning, Profile, Analytics, Coach, Notifications, and Calendar routes.
- Typed Pydantic response models for mobile contracts.
- Future Planning apply is idempotent and does not duplicate drafts on retry.
- Duplicate user/device provisioning races are handled.
- SQLAlchemy hides parameter values in errors.
- JSON/audio/default request-body limits are applied before parsing.
- Private responses use no-store/no-cache headers.
- Release builds require HTTPS; loopback cleartext is debug-only.
- Ownership checks protect cross-user plans/tasks/Future Planning operations.

## 4. Verification completed

- Android: clean build from `D:\CoachAI`, 45 tasks executed, `BUILD SUCCESSFUL`.
- Android unit tests: Schedule date policy, navigation stack, analytics window policy, focus notification formatting, notification channels, repositories, themes/settings.
- Backend: 69 Future Planning/application tests passed.
- Notification registration/tenant/application focused regression: 26 tests passed, including stable PostgreSQL device/token locking (2026-11-11).
- Request-body middleware: declared and streamed 413 tests passed.
- Task lifecycle tested directly: create → start → complete with version increments.
- Future apply replay tested directly: same idempotency key returns the same operation ID without duplicates.
- A focused 56-test backend run found one outdated test fixture; the fixture was corrected and the failed case passed on rerun.
- Device checks: task create, More, Start, focus mode, Complete, Future Planning apply/undo, profile load, Home/API loads, and Analytics window requests.
- Full backend regression on 2026-11-11: 479 tests passed, 1 skipped.
- Android unit tests and `assembleDebug` passed after the auth UI/startup and Future Planning duplicate-Apply fixes.
- Headless API 35 emulator smoke test passed on 2026-11-11: cold launch completed, Login/Create account/Forgot password rendered with the restored orange/navy design and decorative artwork, and AndroidRuntime reported no crash.
- Firebase Android configuration was refreshed on 2026-11-11 with Android and Web OAuth clients. Gradle generated `default_web_client_id`, the Android test/build passed, and Google Sign-In opened the system Credential Manager without a developer/configuration error.
- Firebase-to-backend authentication was verified end to end on 2026-11-11 with a disposable Email/Password account: Firebase issued the ID token, authenticated `GET /v1/profile` returned `200`, then the Firebase account and its local PostgreSQL user were deleted.
- First-login provisioning now has a deterministic race-path regression test proving one user and one profile row survive a parallel unique-key conflict.
- Future Planning rejects repeated Apply/Undo actions while a mutation is in flight and rejects a successful Apply response without a valid operation ID.

## 5. Known follow-up work

### Required before production

1. Configure a production HTTPS API URL using `COACHAI_RELEASE_API_BASE_URL`.
2. Use a production PostgreSQL database and run the Alembic migrations.
3. Configure secrets in the deployment environment, never in Git.
4. Enable Firebase App Check for the custom backend.
5. Add monitoring, crash reporting, alerting, backups, and a release CI pipeline.
6. Create a signed release keystore and build a release/AAB artifact.

### External integrations

- Real remote FCM push still needs server credentials/configuration and the notification worker running.
- Google Calendar needs the final redirect URI, OAuth secret/configuration, and reconciliation worker.
- Ask Coach depends on a valid Gemini/Google AI key, quota, and network. A provider `ServerError` was observed once; the app now fails safely, but production observability and provider retry metrics should be added.

### Authentication edge case

Resolved in the Android client: authenticated repositories now force-refresh the
Firebase ID token and retry once after `401`. Parallel failures for the same rejected
token share the refreshed token. `403` responses are represented separately and are
not retried. Mutation retries reuse the same request body and idempotency key.

### Maintenance warnings

- Some Firebase Messaging and WebView APIs used by the current SDK emit deprecation warnings.
- Replace deprecated icon variants with AutoMirrored variants where appropriate.
- Run the complete HTTP TestClient suite in CI using the locked FastAPI/Starlette/httpx2 environment.

## 6. Local setup

### Backend

```powershell
cd D:\CoachAI
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-lock.txt
Copy-Item .env.example .env
# Fill the local .env values without committing it.
.\.venv\Scripts\python.exe -m uvicorn fastapi_app:app --env-file .env --host 127.0.0.1 --port 8000
```

For a USB-connected Android phone using the debug loopback URL:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" reverse tcp:8000 tcp:8000
```

### Android

Place the Firebase Android config at:

`D:\CoachAI\android\app\google-services.json`

It is intentionally ignored by Git.

Build and test:

```powershell
cd D:\CoachAI\android
.\gradlew.bat testDebugUnitTest assembleDebug
```

APK output:

`D:\CoachAI\android\app\build\outputs\apk\debug\app-debug.apk`

## 7. Files intentionally not committed

- `.env`
- `android/app/google-services.json`
- API keys, OAuth secrets, service-account JSON, release keystores
- APK/AAB files
- Gradle/build caches
- logs, UI dumps, screenshots, temporary databases, and runtime output

Use `.env.example`, `README.md`, `FRONTEND_HANDOFF.md`, `android/README.md`, and this file as the onboarding set.

## 8. Recommended next developer order

1. Clone `main` and provide local Firebase/environment files.
2. Run backend tests and Android unit tests.
3. Provide a refreshed Firebase Android config with Google Web OAuth client and SHA fingerprints.
4. Finish production FCM worker and Calendar OAuth deployment configuration.
5. Test clean install, registration, sign-in, token refresh, task lifecycle, rescheduling, focus notification, Future Planning, Analytics windows, Coach, Profile, and sign-out.
6. Add CI, signed release build, monitoring, and store-release checks.

