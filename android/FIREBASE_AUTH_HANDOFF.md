# CoachAI Android — Firebase Auth handoff

This project contains the Android frontend. Email/password registration, sign-in,
password-reset deep links, Google credential sign-in, Firebase session persistence,
backend bearer-token injection, and signed-out routing are implemented.

## Project details

- Application ID: `com.zenmaestro.app`
- Minimum Android SDK: 26
- UI: Jetpack Compose with Light and Dark themes
- API base URL is read from `COACHAI_API_BASE_URL` in `gradle.properties`
- The local development URL currently uses `http://127.0.0.1:8000` with `adb reverse tcp:8000 tcp:8000`

## Firebase integration point

Repositories run authenticated calls through the shared retry helper in:

`app/src/main/java/com/coachai/android/data/remote/AuthenticatedRequest.kt`

The application injects `FirebaseAuthTokenProvider` in:

`app/src/main/java/com/coachai/android/CoachAiApplication.kt`

`FirebaseAuthTokenProvider` reads the current Firebase user's ID token. A backend
`401` forces one token refresh and retries the same request once. Parallel failures
for the same rejected token share the refreshed token. A `403` is never retried.
Mutation retries preserve their original body and idempotency key.

## Backend contract

Every private API request must include:

`Authorization: Bearer <Firebase ID token>`

The backend verifies the Firebase project ID and derives the user identity from the verified token. Do not send a user ID from the UI as a substitute for authentication.

## Required Firebase work

1. Place the correct uncommitted `google-services.json` in `android/app`.
2. Enable Email/Password and Google providers in the selected Firebase project.
3. Add debug/release SHA fingerprints and ensure the downloaded config contains a
   Web OAuth client so `default_web_client_id` is generated for Google sign-in.
4. Configure the backend `FIREBASE_PROJECT_ID` to match that Firebase project.
5. Configure and verify the authorized password-reset link domain.

## Acceptance checks

- Signed-out users cannot open private account data.
- A signed-in user can load and edit Profile.
- Analytics and Notification Preferences send the same Firebase bearer token.
- A `401` refreshes the Firebase token and retries once; a `403` does not retry.
- Release builds contain no fixed test token or Firebase secret.
- Production builds use an HTTPS API URL instead of the local ADB URL.

## Important current limitations

- Firebase configuration files and credentials are intentionally not committed.
- The refreshed local config matches `com.zenmaestro.app` and contains both the
  Android and Web OAuth clients. `default_web_client_id` is generated successfully,
  and the API 35 emulator opens Android's Credential Manager account selector.
- End-to-end Firebase authentication was verified on 2026-11-11 without using a
  personal account: a temporary Email/Password user obtained a real Firebase ID
  token, `GET /v1/profile` returned `200`, and both the Firebase account and local
  PostgreSQL test user were deleted after the check.
- Google Credential Manager configuration is verified up to the account chooser;
  completing Google account selection still requires an interactive user sign-in.
  Do not commit the local Firebase config.
- Production Firebase, backend, reset-link, Google OAuth, and two-account isolation
  checks still require real-device/integration testing.
