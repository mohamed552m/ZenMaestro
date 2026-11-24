# CoachAI Backend Operations

This runbook describes how to deploy and operate the framework-neutral CoachAI
backend core and its thin authenticated FastAPI transport. It does not
authorize starting background work inside a FastAPI process.

## Production invariants

- PostgreSQL is the production database. SQLite and `coach_ai.db` are only for
  isolated compatibility tests and legacy reference; they are never an
  automatic runtime fallback.
- Alembic is the only production schema upgrade mechanism. The required
  revision is `20261009_0002` until a later reviewed migration changes it.
- Every client operation is derived from a verified `AuthenticatedPrincipal`.
  A client-supplied user ID never selects the tenant.
- Each request or bounded worker cycle gets a short-lived unit of work. No
  transaction or checked-out connection may survive that boundary.
- External calls do not run inside a database transaction. Durable outbox or
  Calendar operation rows bridge database commits and provider work.
- Notification, Calendar, and maintenance loops are separate supervised
  processes. They must not run in Streamlit or in a future web-worker lifespan.
- Database instants are UTC. User-facing calendar calculations use the user's
  stored IANA timezone, such as `Africa/Cairo`.
- Logs, health output, and metric labels must never contain credentials,
  provider payloads, push tokens, notification bodies, or raw exceptions.

## Process topology

Run these as separate deployment roles, each with its own process lifecycle:

1. **Migration job** — runs `python -m alembic upgrade head` once for a release,
   verifies the head, and exits.
2. **Streamlit UI** — runs `python -m streamlit run app.py`. It is a client of
   the same application services and is not a worker host.
3. **Notification worker** — runs `python -m notifications`. It consumes the
   notification outbox and provider delivery queue with database leases.
4. **Calendar worker** — runs `python calendar_worker.py`. It performs durable
   Google Calendar sync, event mutation, and reconciliation intents.
5. **Maintenance worker** — runs `python main.py`. It performs idempotent,
   timezone-aware stale-task cleanup. `main.py` intentionally exposes no web
   application.
6. **FastAPI transport process** — runs
   `python -m uvicorn fastapi_app:app`. It exposes operational probes and the
   reviewed API routes documented in `FRONTEND_HANDOFF.md`; it embeds no worker.

Use a real process supervisor or container orchestrator with restart policies,
termination grace periods, and one role per process. Multiple notification or
Calendar workers are supported by claim/lease semantics, but scale only after
observing queue lag and provider limits.

## Runtime and locked dependencies

Use Python 3.12 and the reviewed exact-version lock file in production:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-lock.txt
.\.venv\Scripts\python.exe -m pip check
```

`requirements.txt` is the human-maintained direct dependency list; it is not
the production lock. `requirements-lock.txt` pins direct and transitive
versions but currently does not contain artifact hashes. If hash-locked wheels
become a deployment requirement, generate them for every supported platform in
a disposable environment rather than hand-editing the existing lock.

To refresh the lock:

1. create a disposable Python 3.12 environment;
2. resolve from the reviewed direct dependencies;
3. replace the lock as one reviewable change;
4. run `pip check`, the complete unit suite, PostgreSQL verification, and the
   Streamlit smoke test; and
5. record the Python version, operating system, and package diff in the release
   evidence.

Never install an unbounded package directly into a production environment.

## Configuration and secret handling

Prefer an operating-system secret store or deployment secret manager. A local
`.env` is development convenience only and must remain untracked. Restrict
secret-file permissions to the service account and keep key files outside the
repository and backup export directories.

### Core settings

- `DATABASE_URL` — required PostgreSQL SQLAlchemy URL. There is no default.
- `FIREBASE_PROJECT_ID` — Firebase audience used for mobile ID-token
  verification. This is distinct from the push provider setting below.
- `COACHAI_TOKEN_ENCRYPTION_KEY` — one Fernet key for encrypted Google OAuth
  credentials, or use the rotation-capable keyring settings.
- `COACHAI_TOKEN_ENCRYPTION_KEYS` — JSON object mapping key IDs to Fernet keys.
- `COACHAI_TOKEN_ACTIVE_KEY_ID` — active key ID, default `primary`.
- `GOOGLE_API_KEY` — Gemini provider credential for planning and voice
  transcription.
- `COACHAI_AI_TIMEOUT_SECONDS` — bounded AI request timeout, `0.1` to `120`,
  default `15`.
- `COACHAI_CHAT_MODEL` — primary low-latency Gemini chat model, default
  `gemini-3.1-flash-lite`.
- `COACHAI_CHAT_FALLBACK_MODEL` — one bounded fallback used only for
  transient provider failures, default `gemini-3.5-flash`.
- `COACHAI_CHAT_TOTAL_TIMEOUT_SECONDS` — hard deadline for the complete
  isolated chat conversation, including tool rounds, default `30`.
- `COACHAI_PLANNER_MODEL` / `COACHAI_PLANNER_FALLBACK_MODEL` — primary and
  single transient-failure fallback for today's AI plan.
- `COACHAI_FUTURE_PLANNER_MODEL` / `COACHAI_FUTURE_PLANNER_FALLBACK_MODEL` —
  primary and single transient-failure fallback for Future Planning intent.
- `COACHAI_VOICE_MODEL` / `COACHAI_VOICE_FALLBACK_MODEL` — primary and single
  transient-failure fallback for speech transcription.
- `COACHAI_EXTERNAL_TIMEOUT_SECONDS` — general external timeout, `1` to `120`,
  default `20`.

Database pool settings are validated at startup:

- `COACHAI_DB_POOL_SIZE`, default `5`, maximum `100`
- `COACHAI_DB_MAX_OVERFLOW`, default `10`, range `0` to `200`
- `COACHAI_DB_POOL_TIMEOUT_SECONDS`, default `30`, maximum `300`
- `COACHAI_DB_POOL_RECYCLE_SECONDS`, default `1800`, maximum `86400`

The pool enables pre-ping and pins PostgreSQL sessions to UTC.

### Google Calendar

- `GOOGLE_CLIENT_ID`
- `GOOGLE_CLIENT_SECRET`
- `GOOGLE_REDIRECT_URI`
- the token-encryption settings above
- `COACHAI_CALENDAR_WORKER_ID`, optional unique worker name
- `COACHAI_CALENDAR_POLL_SECONDS`, default `2`, range `0.1` to `300`
- `COACHAI_CALENDAR_BATCH_SIZE`, default `10`, range `1` to `100`
- `COACHAI_CALENDAR_LEASE_SECONDS`, default `300`, range `30` to `86400`
- `COACHAI_CALENDAR_MAX_ATTEMPTS`, default `5`, range `1` to `100`
- `COACHAI_CALENDAR_LOG_LEVEL`, default `INFO`

For key rotation, deploy the old and new keys together in
`COACHAI_TOKEN_ENCRYPTION_KEYS`, switch `COACHAI_TOKEN_ACTIVE_KEY_ID` to the new
key, rewrite envelopes that report `needs_rotation`, verify decryption, and
only then remove the old key. Losing every configured key makes existing Google
tokens unrecoverable and requires users to reconnect.

### Firebase Cloud Messaging and APNs

Push providers remain off unless explicitly enabled. The supported variables
are documented in [NOTIFICATION_PUSH.md](NOTIFICATION_PUSH.md). Important
distinctions are:

- `COACHAI_FCM_PROJECT_ID` configures FCM delivery;
- `FIREBASE_PROJECT_ID` configures mobile authentication token verification;
- service-account JSON and APNs `.p8` files are secrets even when their paths
  are not; and
- `COACHAI_PUSH_TIMEOUT_SECONDS`, default `10`, bounds provider calls.

Notification-worker tuning is documented in
[notifications/WORKER_RUNTIME.md](notifications/WORKER_RUNTIME.md). Its startup
validation refuses a lease or health window shorter than the worst-case serial
push batch.

### Maintenance

- `COACHAI_MAINTENANCE_POLL_SECONDS`, default `60`, range `1` to `3600`
- `COACHAI_MAINTENANCE_LOG_LEVEL`, default `INFO`

## Release and migration procedure

The detailed migration design and recovery constraints are in
[DATABASE_MIGRATIONS.md](DATABASE_MIGRATIONS.md). Use this deployment sequence:

1. Build an immutable environment from `requirements-lock.txt` and run
   `python -m pip check`.
2. Verify every required secret by name without printing its value.
3. Record the application revision, Python version, PostgreSQL version, current
   Alembic revision, and expected Alembic head.
4. Stop all writers and all three workers, or place the deployment in a mode
   that guarantees no writes.
5. Take and restore-verify a backup using the procedure below.
6. Run the migration against a disposable restore first.
7. Run schema, ownership, transaction, and application smoke tests against that
   restore.
8. Run `python -m alembic upgrade head` once against production.
9. Verify `python -m alembic current` reports `20261009_0002`.
10. Start the UI/transport role, then the workers, and verify readiness, queue
    lag, errors, and pool pressure before ending maintenance mode.

Do not use `metadata.create_all()` or `alembic stamp head` to upgrade an
existing PostgreSQL database. Application startup rejects a missing or stale
`alembic_version` rather than silently modifying the schema.

## Connection and transaction lifecycle

Create one `DatabaseFactory` per process. It owns the SQLAlchemy engine and
pool. For each client operation or bounded worker poll:

1. enter `UnitOfWork(factory)`;
2. perform ownership reads and the mutation inside an explicit database
   transaction where required;
3. commit the complete mutation, its idempotency result, sync changes, and any
   outbox/operation intent atomically;
4. leave the unit of work, which rolls back any leftover implicit transaction
   and releases the connection; and
5. close the factory during graceful process shutdown.

Never hold a connection while waiting on Gemini, Firebase, APNs, or Google
Calendar. The Calendar worker intentionally claims work, releases the
connection, performs bounded provider I/O, then opens a fresh unit of work to
record the result.

## Shared rate limits and external calls

The principal-bound AI day-plan draft uses `DatabaseRateLimiter`, an atomic
fixed window keyed by principal, stable operation scope, and window. The
PostgreSQL table stores only counters and expiry times, not request content, and
coordinates all UI/transport replicas. Rejected increments roll back; expired
buckets are cleaned opportunistically. Legacy recommendation and chatbot code
still uses an in-process limiter; those paths are not sufficient for a
multi-process transport and must not be exposed there until they use the shared
control.

External adapters must apply an explicit timeout on every attempt. Generic
resilience primitives classify failures as rate-limited, transient,
authentication, permanent, configuration, or unknown; bound retries and
circuit recovery; and emit low-cardinality retry metrics. An SDK that ignores
the supplied timeout is not made safe merely by wrapping it.

The circuit-breaker and generic retry objects are reusable primitives, not a
claim that every provider call is currently routed through one shared circuit.
Calendar and notification workers use their own durable retry state; AI calls
use provider-specific bounded timeout/retry configuration. Verify each
adapter's actual wiring before relying on a circuit-open alert.

Do not stack an uncoordinated generic retry loop around a non-idempotent remote
mutation. Notification and Calendar writes use their durable provider-specific
queues, leases, attempt counts, and reconciliation semantics. Authentication,
configuration, and permanent client failures fail closed rather than retrying
forever.

## Logging and correlation

Workers configure the redacting JSON formatter. A future transport must do the
same and wrap each operation in `correlation_context`, using:

- a generated or validated correlation ID;
- the durable idempotency/operation ID when available; and
- the trusted principal ID, never an unverified body field.

Log event names, stable error codes, operation IDs, durations, counts, and
exception class names. Do not log request bodies, model prompts, transcripts,
notification text, OAuth codes, Calendar payloads, device tokens, connection
URLs, provider responses, or tracebacks from external calls. The redactor is
defence in depth, not permission to pass arbitrary objects to the logger.

## Liveness, readiness, and worker supervision

`operational_health.py` provides framework-neutral primitives. The FastAPI
transport exposes process-only `GET /health` and dependency-aware `GET /ready`
without adding business logic or returning configuration and exception details.

- **Liveness** answers whether the process loop is responsive. Do not make it
  depend on a remote provider.
- **Readiness** includes liveness plus a bounded database ping, correct schema
  revision, valid mandatory configuration, and the role's ability to claim
  work. A failed dependency must make the role unready while leaving it live
  enough to report and recover.
- `WorkerHeartbeat.snapshot()` reports `healthy`, `degraded`, or `unhealthy`,
  lag, consecutive failures, and only the last exception type.
- `NotificationWorkerRuntime.health_snapshot()` and `metrics_snapshot()` are
  secret-free. Snapshot hooks can publish them after each cycle.

The shipped Calendar and maintenance entry points maintain in-process
heartbeats and metrics hooks, but they do not themselves host a probe server or
remote metric exporter. Production deployment must attach an exporter/sidecar
or supervise process exit plus an external heartbeat. Treat a process that is
alive but has no recent successful poll as unready.

Graceful termination uses SIGINT/SIGTERM. Allow at least the configured lease
and provider timeout window for the active bounded batch to finish. If a
process is killed, another worker may recover the stale lease after the lease
window.

## Metrics

`BackendMetrics` is exporter-neutral. The default sink is intentionally a
no-op; production must attach a Prometheus, OpenTelemetry, or equivalent
adapter with low-cardinality labels. Canonical hooks are:

- `coachai_worker_cycles_total{worker,result}`
- `coachai_worker_cycle_duration_seconds{worker}`
- `coachai_worker_lag_seconds{worker}`
- `coachai_notification_oldest_queued_seconds{channel}`
- `coachai_external_retries_total{component,failure_class}`
- `coachai_db_pool_connections{state}`
- `coachai_calendar_failures_total{operation,failure_class}`
- `coachai_ai_calls_total{provider,model,result}`
- `coachai_ai_call_duration_seconds{provider,model}`
- `coachai_ai_input_tokens_total{provider,model}`
- `coachai_ai_output_tokens_total{provider,model}`
- `coachai_ai_estimated_cost_microusd_total{provider,model}`

The notification runtime additionally exports secret-free cycle, outbox, push,
invalid-token, provider-latency, outbox-lag, and push-lag counters/gauges in its
snapshot. Never use user IDs, email addresses, device IDs, tokens, task titles,
or operation IDs as metric labels.

The AI call-count and duration hooks are wired into planning, interpretation,
and transcription. Token and estimated-cost counters are exporter hooks, but no
current provider adapter supplies usage/cost values to them; zero or absent
series must not be interpreted as zero provider spend. Database-pool and
notification-lag hooks likewise require a deployment adapter or the
notification runtime snapshot to publish them.

Initial alerting should cover:

- no successful worker cycle inside the role's stale window;
- oldest queued notification or Calendar operation increasing across several
  poll intervals;
- repeated lease recovery or exhausted attempts;
- rising permanent authentication/configuration failures;
- checked-out database connections approaching pool size plus overflow;
- database ping or schema readiness failure;
- sustained AI/provider failure or rate-limit growth; and
- unexpected AI token/cost growth.

Choose numeric paging thresholds from measured traffic and provider quotas;
the defaults are operational starting points, not an availability SLA.

## PostgreSQL backup procedure

Use PostgreSQL 16 client tools compatible with the server. The backup role
needs read access to the CoachAI database but should not own application
objects. Store credentials in a restricted `PGPASSFILE`; do not put passwords
in commands or logs.

Example PowerShell procedure:

```powershell
$env:PGHOST = '127.0.0.1'
$env:PGPORT = '5432'
$env:PGDATABASE = 'coachai'
$env:PGUSER = 'coachai_backup'
$env:PGPASSFILE = 'C:\secure\postgres\pgpass.conf'
$coachaiStamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$coachaiBackup = "C:\secure\coachai-backups\coachai_$coachaiStamp.dump"

python -m alembic current
pg_dump --format=custom --compress=9 --no-owner --no-privileges --file=$coachaiBackup
pg_restore --list $coachaiBackup
Get-FileHash -Algorithm SHA256 $coachaiBackup
```

Record the checksum alongside the application revision, Alembic revision,
database/server version, UTC timestamp, database name, and row counts for users,
plans, tasks, task segments, notifications, Calendar operations, and
idempotency records. Encrypt backups at rest and define retention/deletion in
the deployment policy. Backups contain personal/productivity data, device push
tokens, and encrypted OAuth envelopes, so they are secret-bearing even though
provider master credentials and encryption keys are stored separately.

A backup is not accepted until it restores successfully into a disposable
database.

## Restore verification and recovery

Never test a restore over the production database. Create a new, clearly named
database and restore into it:

```powershell
$coachaiRestoreDb = 'coachai_restore_verify'
createdb --template=template0 $coachaiRestoreDb
$env:PGDATABASE = $coachaiRestoreDb
pg_restore --exit-on-error --single-transaction --no-owner --no-privileges --dbname=$coachaiRestoreDb $coachaiBackup

$env:DATABASE_URL = 'postgresql+psycopg2://coachai_restore:<PASSWORD>@127.0.0.1:5432/coachai_restore_verify'
python -m alembic current
python -m alembic upgrade head
```

Use a secret-injected `DATABASE_URL` in a real run; the placeholder above must
not be copied into automation. Then:

1. verify the expected Alembic head and schema constraints/indexes;
2. compare recorded critical row counts and sample tenant ownership joins;
3. run the PostgreSQL verification tools against the disposable target;
4. run application reads and a reversible test-account mutation;
5. verify workers can claim, complete, and recover a test operation; and
6. delete the disposable database only after evidence is retained and the
   exact target has been independently confirmed.

For production recovery, keep the failed database unchanged for diagnosis,
restore to a new database, validate it, stop writers, and cut over the
connection setting. Do not perform an in-place destructive restore. Keep the
old database read-only until the recovery window expires.

The baseline migration is forward-only because a generic downgrade could drop
adopted user data. Recover a failed baseline from a verified backup. Revision
`20261009_0002` may be downgraded because it removes only disposable
rate-limit buckets; see the migration document for the exact policy.

## Failure-response playbooks

### Database unavailable or schema mismatch

1. Mark the affected roles unready and stop accepting mutations.
2. Leave queued work untouched; do not bypass the database with in-memory
   writes.
3. Check connectivity, pool saturation, PostgreSQL health, and
   `alembic current` without printing the connection URL.
4. Restore service or migrate to the required head, then restart one role at a
   time and observe backlog drain.

### Notification backlog or provider failure

1. Check outbox lag, push lag, cycle failures, leases, and provider-specific
   classifications.
2. Fix authentication/configuration failures before scaling workers; more
   workers do not repair bad credentials.
3. Let transient/rate-limited deliveries follow bounded retry and
   `Retry-After` guidance.
4. Permanent-token failures disable and clear only the affected device token.
   The Android client must register a fresh token when available.
5. Never mark provider acceptance as physical device delivery.

### Calendar backlog or revoked authorization

1. Inspect operation status, attempt count, queue age, and safe failure class.
2. Allow transient failures to retry with the durable queue's bounded backoff.
3. Revoked/invalid authorization is permanent: encrypted credentials are
   removed and the user must reconnect Google Calendar.
4. Do not manually mark operations complete. Requeue with a new idempotency key
   only after establishing whether the remote mutation occurred.
5. Segment sync state and durable reconciliation remain the source of truth
   after uncertain provider outcomes.

### AI provider failure or spend anomaly

1. Disable the AI feature or fail it closed; deterministic scheduling and
   stored plans remain available.
2. Check rate-limit buckets, timeout/retry metrics, token counts, and model
   configuration without logging prompts or transcripts.
3. Model output remains an untrusted draft. Never bypass strict validation or
   explicit Apply to recover availability.

### Token-encryption key incident

1. Stop Calendar workers and authorization completion.
2. Preserve the encrypted rows and determine which key IDs are affected.
3. Rotate with old and new keys present, verify rewritten envelopes, then
   retire the old key.
4. If old keys are unavailable, delete unusable credentials through the
   principal-bound disconnect flow and require reauthorization.

## Production verification record

For each release, retain evidence for:

- exact unit-test count and result;
- disposable PostgreSQL 16 fresh-migration and upgrade-migration result;
- restore test result and backup checksum;
- tenant, transaction, concurrency, idempotency, timer, sync, notification,
  Calendar, scheduler, planner, and analytics checks;
- Streamlit startup/status/settings/scheduling/Future Planning smoke tests;
- schema revision and dependency lock checksum;
- log-redaction and secret-scan result; and
- provider checks explicitly separated into deterministic fake, sandbox
  provider acceptance, and physical-device observation.

Real Firebase ID-token verification depends on valid project credentials and a
test identity. Real Android FCM delivery is **NOT VERIFIED** by the local fake
provider or queue tests and requires external credentials plus a disposable
Android device. Real APNs delivery has the same external dependency. Record
these as unverified until executed; do not infer them from passing unit tests.

This runbook does not itself declare the backend ready. Readiness is the result
of the complete release evidence and security audit, including confirmation
that `coach_ai.db` and all secret/database artifacts remain unchanged and
unstaged.
