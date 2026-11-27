# ZenMaestro Project State

Updated: 2026-11-27 (post-deployment follow-up)

## Scope and safety

- Working branch: `main`; deployed commit: `3fc1108` (`zen/main`).
- `origin` is the Yousef repository and must never receive this work; `zen` is `mohamed552m/ZenMaestro`.
- The four verified commits through `3fc1108` were merged, pushed to `zen/main`, and deployed manually. No further push, redeploy, or production mutation is authorized without approval.
- Android Studio has an AGP compatibility warning, but command-line Gradle builds work. Do not change AGP, Gradle, or Java without a separate reason.

## Confirmed production state

- Railway Firebase `401` root cause was an invalid `FIREBASE_PROJECT_ID` value (`your-firebase-project-id`). It was manually corrected to the real Firebase project ID and Railway was redeployed.
- Railway `/ready` and Neon connectivity are healthy.
- Production logs subsequently showed HTTP 200 for Home, Profile, Calendar, Analytics, Future Planning Candidates, and Notification Preferences.
- Both Android API base URLs point to `https://zenmaestro-production.up.railway.app`.
- The modified Android debug APK was built and installed successfully from the command line.
- Railway's invalid `GOOGLE_API_KEY` was replaced manually. Minimal inference now succeeds from Railway with both configured Gemini models.
- Emulator checks now confirm Ask Coach answers a greeting and a schedule-grounded plan question using current task data.

## Deployed fixes

- Firebase token verification now distinguishes invalid credentials from Firebase infrastructure/certificate failures and supports a validated Railway service-account secret.
- Gemini planner, future planner, coach report, voice, and chatbot paths share bounded fallback classification and safe diagnostics.
- Ask Coach runs in an isolated worker with a hard total deadline and a grounded local fallback.
- Android maps authentication, provider availability, rate-limit, timeout, and server failures more accurately.
- Voice transcription is moved off the async request loop.

## Ask Coach diagnosis

- The configured primary and fallback model identifiers are visible to the current Gemini account.
- A small primary-model request succeeded, while the configured fallback intermittently returned provider `504`.
- The complete Ask Coach request timed out with the old 15-second provider deadline. The same complete path succeeded with its tool call in 13.11 seconds when allowed a 35-second provider deadline.
- Local defaults are therefore prepared as `COACHAI_AI_TIMEOUT_SECONDS=35` and `COACHAI_CHAT_TOTAL_TIMEOUT_SECONDS=80`. This leaves up to 35 seconds for each model plus bounded process overhead; Android waits 90 seconds for Coach responses.
- Railway uses a 35-second provider deadline and an 80-second total chat deadline. The prior HTTP 400 failure was traced to the invalid production API key, not Railway or the configured deadlines.
- A more complex Arabic planning question still reached the bounded, data-grounded local fallback. The precise slow stage was not visible in existing logs, so content-free timing was added for the initial model call and every tool continuation. No timeout was increased.
- The Android Coach read timeout remains 90 seconds, safely above the backend's 80-second total deadline.
- Gemini replies contain valid Markdown, but the current chat bubble displayed its markers literally. Local Android changes now render headings, bold text, and lists and detect Arabic for RTL alignment.
- Home now shows a lightweight native skeleton while its local WebView initializes, retains the last confirmed snapshot during refresh, and keeps it on transient network failure instead of flashing a blank/loading screen.

## Verification

- Focused backend regression suite: 36 passed, 0 failed (`test_ai_boundary`, `test_ai_provider_runtime`, `test_backend_auth`).
- Current focused checks: 3 backend chat tests and 2 Android Markdown tests passed; Android debug Kotlin compilation succeeded.
- Isolated live Ask Coach smoke: complete primary-model tool path passed; no production data was used.
- `git diff --check` passed.
- Earlier current-branch Android unit tests and debug assembly passed; the user also rebuilt and installed the modified APK manually.

## Deployment preparation

- `zen` points to `https://github.com/mohamed552m/ZenMaestro.git`; `origin` still points to Yousef and must not be pushed.
- Railway is connected to the GitHub `main` deployment branch. The current local `main` and `zen/main` are both at the production baseline `3fc1108`.
- Python remains `3.12.14`; no Gradle, AGP, Java, database schema, or Alembic migration changed.
- No new mandatory secret is introduced. `FIREBASE_SERVICE_ACCOUNT_JSON` is accepted as raw or base64 JSON and is validated against `FIREBASE_PROJECT_ID`; existing Firebase security remains enabled.
- Required Railway deadline values for the prepared release are `COACHAI_AI_TIMEOUT_SECONDS=35` and `COACHAI_CHAT_TOTAL_TIMEOUT_SECONDS=80`. Existing model, Firebase, database, and API-key variables stay unchanged.
- Rollback requires no database action: redeploy the previous successful Railway deployment for `c147fe4`, or revert the release commits on `main` and redeploy.

## Remaining blockers / checks

1. After a future approved deployment, capture only the new `Chat provider call` timing lines for one Arabic planning request to identify whether latency is in `initial` or a `tool_round`; do not copy prompts or credentials.
2. Manually verify Markdown/RTL rendering and the Home skeleton/cache behavior on the existing emulator build.
3. Verify notification preference save serialization and truthful feedback.
4. Investigate the two same-time Break entries before changing scheduling logic.
5. Runtime-verify task persistence/duplicate prevention, voice transcription on a real device, and FCM delivery.
