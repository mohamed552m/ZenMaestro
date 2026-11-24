# Zen Maestro — Release Readiness Roadmap

This is the working release checklist. A feature is complete only after its
backend contract, Android behavior, error states, and a real-device flow have
all been verified.

## Milestone 0 — Stable identity and connectivity (P0)

- [x] Install Firebase Admin verification on the backend.
- [x] Keep production revocation checks secure; allow explicit signature-only local testing.
- [x] Verify authenticated Home/Profile/Analytics/Calendar/Future endpoints return HTTP 200.
- [x] Make first-login user provisioning concurrency-safe under parallel screen loading.
- [x] Fix sign-in/registration sequencing so registration waits for the display name.
- [x] Isolate every user-scoped ViewModel and navigation stack by Firebase UID.
- [ ] Show a complete Profile account card and verify it on device.
- [ ] Test registration, login, Google, reset, sign-out, and a second account.

## Milestone 1 — Core task loop (P0)

- [ ] Home empty/loading/error/data states.
- [ ] Create, edit, delete, start, pause, resume, complete, fail, and defer tasks.
- [ ] Verify timer background/foreground behavior.
- [x] Make Schedule server-authoritative after successful writes.
- [ ] Verify day strip loads selected days and past days are read-only.
- [ ] Verify move, smart schedule, breaks, fixed tasks, conflicts, and Undo.
- [ ] Refresh every dependent screen after mutations.

## Milestone 2 — Future Planning (P0)

- [x] Add candidates, preview, apply, and Undo API contracts.
- [x] Add a dedicated mobile-first page reached from Schedule.
- [x] Support day/range, existing/draft tasks, duration, priority, deadline, preview, and apply.
- [x] Reject dates before the user's local today.
- [x] Cancel stale candidate loads when dates change quickly.
- [x] Normalize old constraints into the selected horizon.
- [x] Prevent a second Apply from duplicating drafts.
- [x] Refresh Schedule days and Home after Apply/Undo.
- [ ] Add keyboard safety and Android tests.
- [ ] Real-device preview → apply → inspect days → undo → inspect restoration.

## Milestone 3 — Notifications and external services (P0/P1)

- [ ] Register the real FCM token, not an installation ID.
- [x] Make duplicate device/token registration concurrency-safe.
- [ ] Test permission, preferences, foreground/background push, and tap routing.
- [ ] Apply sound/vibration preferences to notification channels.
- [ ] Test AI draft, voice transcription, Ask Coach, and Coach Report.
- [ ] Test Google Calendar connect, callback, selection, sync, reconciliation, and disconnect.
- [ ] Never mark an external integration complete from code inspection alone.

## Milestone 4 — Product behavior and polish (P1)

- [ ] Bottom tabs replace roots; detail pages push and Back returns one level.
- [ ] Notification taps open the intended screen/task.
- [ ] Keyboard never hides active fields or creates a black gap.
- [ ] Theme, language, time format, haptics, and reduced motion have observable behavior.
- [ ] Verify contrast, empty/loading/error states, retries, and accessibility labels.
- [ ] Remove visible legacy “CoachAI” branding while preserving package compatibility.

## Milestone 5 — Release verification (P0)

- [x] Complete backend regression suite passes (479 tests, 1 skipped, 2026-11-11).
- [ ] Android unit/instrumentation tests and debug/release builds pass.
- [ ] Fresh install: register → profile → task loop → future plan → coach → analytics → notifications → calendar → settings → sign-out.
- [ ] Second-account login proves no first-account data is visible.
- [ ] Network/server/provider failures show safe actionable messages.
- [ ] Produce final APK and deployment checklist.
