# ZenMaestro Project State

Updated: 2026-11-27

## Scope and safety

- Working branch: `fix/teammate-runtime-integration`; baseline: `c147fe4` (`zen/main`).
- `origin` is the Yousef repository and must never receive this work; `zen` is `mohamed552m/ZenMaestro`.
- No push, redeploy, production variable change, or production database mutation has been performed in this branch.
- Android Studio has an AGP compatibility warning, but command-line Gradle builds work. Do not change AGP, Gradle, or Java without a separate reason.

## Confirmed production state

- Railway Firebase `401` root cause was an invalid `FIREBASE_PROJECT_ID` value (`your-firebase-project-id`). It was manually corrected to the real Firebase project ID and Railway was redeployed.
- Railway `/ready` and Neon connectivity are healthy.
- Production logs subsequently showed HTTP 200 for Home, Profile, Calendar, Analytics, Future Planning Candidates, and Notification Preferences.
- Both Android API base URLs point to `https://zenmaestro-production.up.railway.app`.
- The modified Android debug APK was built and installed successfully from the command line.

## Local fixes prepared, not deployed

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
- Railway still explicitly uses a 30-second total deadline, and production does not yet contain these local backend fixes. Production variables must be updated only as part of an approved deployment.

## Verification

- Focused backend regression suite: 36 passed, 0 failed (`test_ai_boundary`, `test_ai_provider_runtime`, `test_backend_auth`).
- Isolated live Ask Coach smoke: complete primary-model tool path passed; no production data was used.
- `git diff --check` passed.
- Earlier current-branch Android unit tests and debug assembly passed; the user also rebuilt and installed the modified APK manually.

## Deployment preparation

- `zen` points to `https://github.com/mohamed552m/ZenMaestro.git`; `origin` still points to Yousef and must not be pushed.
- Railway is connected to the GitHub `main` deployment branch. The current local `main` and `zen/main` are both at the production baseline `c147fe4`.
- Python remains `3.12.14`; no Gradle, AGP, Java, database schema, or Alembic migration changed.
- No new mandatory secret is introduced. `FIREBASE_SERVICE_ACCOUNT_JSON` is accepted as raw or base64 JSON and is validated against `FIREBASE_PROJECT_ID`; existing Firebase security remains enabled.
- Required Railway deadline values for the prepared release are `COACHAI_AI_TIMEOUT_SECONDS=35` and `COACHAI_CHAT_TOTAL_TIMEOUT_SECONDS=80`. Existing model, Firebase, database, and API-key variables stay unchanged.
- Rollback requires no database action: redeploy the previous successful Railway deployment for `c147fe4`, or revert the release commits on `main` and redeploy.

## Remaining blockers / checks

1. Obtain approval before pushing or deploying the prepared backend changes and before changing Railway chat deadlines.
2. After deployment, verify Ask Coach end to end from Android and inspect only sanitized status/type logs if it fails.
3. Investigate the two same-time Break entries before changing scheduling logic.
4. Runtime-verify task persistence/duplicate prevention, voice transcription on a real device, and FCM delivery.
