# backend-spring

CivicPulse's **core API**. Owns (per
[`docs/service-boundaries.md`](../docs/service-boundaries.md)):

- auth, users, and role-based access control
- complaint / officer / reference-data CRUD
- dashboard and analytics APIs
- **every database migration**, including tables backend-node reads

It is the **single writer and single schema owner**. backend-node decides
*what* should be written (semantic matching) and asks this service to write
it.

> **Status:** Phases 0-4. Auth, schema, wards, complaints, incidents,
> dashboard, and `/internal/*` are live. Priority scoring is real and carries
> human-readable reasons.
>
> **Semantic matching (Phase 2) is live.** Creating a complaint commits it and
> returns immediately with `matchingStatus: "pending"`; an `AFTER_COMMIT`
> listener then fires an **async** trigger at backend-node, which embeds, matches
> and calls `POST /internal/incidents/attach` back. A citizen never waits on an
> embedding call.
>
> `NaiveIncidentGrouper` (ward + category + a 14-day window + a 1.5 km radius)
> has been **demoted to the resilience fallback**: it runs only when Node is
> unreachable or the circuit breaker is open, and tags the complaint `degraded`.
> `MatchReconciliationJob` re-runs the real pipeline over those once Node
> recovers, correcting any naive mis-grouping. It is still config-gated on
> `INCIDENT_GROUPING_STRATEGY` and every decision is logged — it is not semantic
> matching and is not presented as such.
>
> See [`docs/service-boundaries.md`](../docs/service-boundaries.md) decision 2
> for the concurrency model, including why a late callback from a slow Node is
> intentional.

---

## Stack

| Concern | Choice |
|---|---|
| Runtime | Java 21 (LTS, per the project-wide version policy) |
| Framework | Spring Boot 4.0.8 — note: Boot 4 defaults to **Jackson 3** (`tools.jackson`), and splits auto-config into per-technology starters |
| Persistence | Spring Data JPA / Hibernate 7, `ddl-auto: validate` |
| Migrations | **Flyway** (`spring-boot-starter-flyway`) |
| Security | Spring Security + `jjwt` 0.12.6, stateless |
| Build | Maven via the committed wrapper (`./mvnw`) |

---

## Setup

```bash
cd backend-spring
cp .env.example .env      # paste your Neon DB_URL; set JWT_SECRET (≥32 chars)
./mvnw spring-boot:run    # http://localhost:8080
```

No local Maven install needed — the wrapper downloads it on first run.
The database is **Neon** (hosted); see **Database (Neon)** in the
[root README](../README.md). On first run Flyway provisions everything: the
`pgvector` extension, every table, and seed wards/departments.

For semantic matching to actually run you also need `INTERNAL_TOKEN` set here
**and** to the same value in `backend-node/.env`, with backend-node running.
Without it, complaints still submit fine — they fall back to naive grouping and
are tagged `degraded`.

### Scripts

| Command | Does |
|---|---|
| `./mvnw spring-boot:run` | Run the service |
| `./mvnw verify` | Compile + run tests (**needs Postgres up**) |
| `./mvnw -DskipTests package` | Build the jar |
| `./mvnw clean` | Remove `target/` |

---

## Environment

Spring Boot does not read `.env` files on its own. `application.yaml` pulls
one in with `spring.config.import: optional:file:.env[.properties]` — present
locally, absent in production where the platform injects real env vars.

| Var | Required | Default | Notes |
|---|---|---|---|
| `DB_URL` | **yes** | — | **JDBC** URL: `jdbc:postgresql://HOST/neondb?sslmode=require`. No credentials in it, and drop Neon's `channel_binding` (libpq-only). **Not** the same format as backend-node's `DATABASE_URL`. |
| `DB_USERNAME` | **yes** | — | |
| `DB_PASSWORD` | **yes** | — | |
| `SERVER_PORT` | no | `8080` | |
| `FRONTEND_ORIGIN` | no | `http://localhost:5173` | Single CORS origin, no wildcards |
| `COOKIE_SECURE` | no | `false` | **Must be `true` over HTTPS** |
| `HEALTH_TIMEOUT_SECONDS` | no | `10` | Ceiling on the `/health` probe. Sized for a Neon cold start; lower for a local DB. |
| `JWT_SECRET` | **yes** | — | **≥32 characters** — startup fails otherwise. `openssl rand -base64 48` |
| `JWT_ACCESS_EXPIRATION` | no | `900000` (15 min) | ms |
| `JWT_REFRESH_EXPIRATION` | no | `604800000` (7 days) | ms |
| `LOG_LEVEL` | no | `INFO` | |
| `WARD_RESOLVER` | no | `centroid` | Which `WardResolver` to use. `centroid` is nearest-seeded-centroid; a future `postgis` would do point-in-polygon. |
| `WARD_RESOLUTION_MAX_KM` | no | `25` | Points further than this from every ward centroid resolve to **no ward** (404) rather than being filed into the least-distant one. |
| `INTERNAL_TOKEN` | for Node | *(blank)* | Shared secret, **both directions**: Node sends it on `/internal/*`, and this service sends it when triggering Node. Must match `backend-node`'s. **Blank denies everything** — an unset secret breaks the integration rather than opening it. Also required by the dev seed script below. |
| `NODE_BASE_URL` | no | `http://localhost:3001` | Where the async matching trigger POSTs `/complaints/{id}/process`. |
| `MATCHING_PROCESS_TIMEOUT_MS` | no | `2500` | Read timeout on that trigger. Short on purpose — a slow Node keeps the `PROCESSING` claim and finishes on its own. |
| `MATCHING_PROCESSING_STALE_SECONDS` | no | `45` | A `PROCESSING` claim older than this is treated as abandoned. **Must match** backend-node's `PROCESSING_STALE_SECONDS`. |
| `MATCHING_CB_FAILURES` | no | `3` | Consecutive connection failures before the circuit breaker opens. |
| `MATCHING_CB_OPEN_SECONDS` | no | `60` | How long Node calls stay suspended once open. |
| `MATCHING_RECONCILE_ENABLED` | no | `true` | `false` removes the scheduled sweep bean entirely. |
| `MATCHING_RECONCILE_INTERVAL_MS` | no | `60000` | Sweep interval. |
| `MATCHING_RECONCILE_BATCH` | no | `25` | Complaints re-processed per sweep. |
| `INCIDENT_GROUPING_STRATEGY` | no | `naive` | The **fallback** matcher, used only when Node is unreachable. `naive` or `none` (leave complaints `pending` for reconciliation instead). |
| `INCIDENT_GROUPING_WINDOW_DAYS` | no | `14` | Fallback: only join incidents opened within this window. |
| `INCIDENT_GROUPING_MAX_KM` | no | `1.5` | Fallback: only join incidents whose centroid is within this radius. |
| `GEOCODING_PROVIDER` | no | `none` | `none` or `google`. Default needs no key and no billing account — addresses simply stay null. |
| `GOOGLE_GEOCODING_API_KEY` | for geocoding | *(blank)* | **Server-side only.** Never reaches the browser, so restrict it by **IP address**, not HTTP referrer. |
| `HTTP_CONNECT_TIMEOUT_MS` | no | `3000` | Bound on outbound third-party calls. |
| `HTTP_READ_TIMEOUT_MS` | no | `5000` | As above. An untimed client pins a request thread when the provider is slow. |
| `FORWARD_HEADERS_STRATEGY` | no | `none` | Whether to trust `X-Forwarded-For` for the client address, which the per-IP login budget keys on. `none` is **safe by default**: with no proxy in front, trusting it would let anyone spoof a fresh IP per request and walk around the limit. Set to `framework` **only** behind a reverse proxy you control. |
| `AUTH_MAX_ATTEMPTS_PER_EMAIL` | no | `5` | Failed sign-ins per address before a lockout. Tight — a real person does not fail five times and then succeed. |
| `AUTH_MAX_ATTEMPTS_PER_IP` | no | `50` | Failed sign-ins per IP across **all** accounts; this is what catches password spraying. Loose, because an office or carrier NAT puts many innocent users on one address. |
| `AUTH_ATTEMPT_WINDOW_SECONDS` | no | `900` | Failures older than this stop counting. |
| `AUTH_LOCKOUT_SECONDS` | no | `900` | How long a key stays locked once its budget is spent. |
| `AUTH_MAX_TRACKED_KEYS` | no | `100000` | Ceiling on tracked rate-limit keys. Email keys are attacker-chosen strings, so the table needs a bound or a flood of invented addresses exhausts memory. |
| `AUTH_REFRESH_REUSE_GRACE_SECONDS` | no | `30` | How long a rotated refresh token is still accepted as a genuine two-tab race rather than a replayed theft. |
| `AUTH_EMAIL_REQUESTS_PER_ADDRESS` | no | `3` | Mail-sending requests per address per window. Every request counts, not just failures — the send *is* the abuse. |
| `AUTH_EMAIL_REQUESTS_PER_IP` | no | `10` | As above, per IP. |
| `AUTH_EMAIL_REQUEST_WINDOW_SECONDS` | no | `3600` | Window and lockout for both email budgets. |
| `EMAIL_PROVIDER` | no | `log` | **`log` is the default and needs no account or key** — the message and its link are printed to the console, so verification and password reset work end to end on a fresh clone. `resend` turns on real delivery. **Never `log` in production**: it would put live reset links in your log aggregator. |
| `RESEND_API_KEY` | for `resend` | *(blank)* | Server-side only. Unused while `EMAIL_PROVIDER=log`. |
| `EMAIL_FROM_ADDRESS` | no | `CivicPulse <onboarding@resend.dev>` | Must be on a domain verified with Resend, or their shared test sender (which only delivers to the account owner's address). |
| `EMAIL_LINK_BASE_URL` | no | `http://localhost:5173` | Where emailed links point — the **frontend**, not this service. Must match how the app is actually reached or every link 404s. |
| `EMAIL_VERIFICATION_TTL_HOURS` | no | `24` | Long: expiry only costs a confused user another request. |
| `EMAIL_RESET_TTL_MINUTES` | no | `60` | Short: that token *is* the account until it is used. |

---

## Routes

Routes here are **prefix-free**. The frontend's `/api/core` prefix is a
routing convention added by the Vite dev proxy
([`frontend/vite.config.ts`](../frontend/vite.config.ts)) and the production
reverse proxy — it must never appear in a `@RequestMapping`.

| Method & path | Access | Status |
|---|---|---|
| `GET /health` | public | **live** — real `SELECT 1`; `200 {status:'ok'}` / `503 {status:'error'}` |
| `POST /auth/register` | public | **live** — always creates a citizen |
| `POST /auth/login` | public | **live** — sets cookies, returns `{user}`; rate limited (429) |
| `GET /auth/me` | cookie | **live** — session restore |
| `POST /auth/refresh` | refresh cookie | **live** — rotates the pair, single-use |
| `POST /auth/logout` | public | **live** — revokes this session, clears cookies, 204 |
| `POST /auth/logout-all` | cookie | **live** — revokes every session for the user, 204 |
| `POST /auth/verify-email` | public | **live** — redeems a verification link, 204 |
| `POST /auth/resend-verification` | cookie | **live** — address taken from the session, 204 |
| `POST /auth/forgot-password` | public | **live** — **always 204**; never says whether the account exists |
| `POST /auth/reset-password` | public | **live** — 204, revokes every session |
| `GET /wards`, `GET /wards/{id}` | public | **live** — municipal reference data |
| `GET /wards/resolve?lat=&long=` | public | **live** — derives a ward from coordinates; 404 beyond 25 km |
| `GET /wards/summary` | **public** | **live** — landing-page strip. **Counts, never rows** |
| `GET /departments`, `GET /departments/{id}` | cookie | **live** |
| `POST /complaints` | public | **live** — anonymous allowed; `wardId` optional, derived from coordinates |
| `GET /complaints` | `officer` | **live** — full complaint text, so not merely `authenticated` |
| `GET /complaints/mine` | `citizen` | **live** |
| `GET /complaints/{id}` | cookie | **live** — citizen reads only their own; **404 not 403** otherwise |
| `PATCH /complaints/{id}` | `officer` | **live** |
| `GET /incidents` (+ `{id}`, `{id}/complaints`, `PATCH`) | `officer` | **live** |
| `GET /dashboard/summary`, `/dashboard/wards/{id}/summary` | `officer` | **live** |
| `POST /internal/incidents/attach` | `X-Internal-Token` | **live** — Node decides, Spring writes (+ the `incident_match_log` row) |
| `POST /internal/incidents/{id}/recompute` | `X-Internal-Token` | **live** — re-derive count/centroid/priority only. No membership change, no log row |
| `GET /internal/complaints/{id}`, `GET /internal/wards/{id}` | `X-Internal-Token` | **live** |
| `/analytics/**` | `officer` | Phase 5 |

`POST /complaints` returns `matchingStatus: "pending"` — grouping runs after the
response. Poll `GET /complaints/{id}` (or watch the logs) to see it reach
`matched`, or `degraded` if the fallback handled it.

Authorization is **default-deny** (`anyRequest().authenticated()`), so a new
controller is protected until someone opens it deliberately. One visible
consequence: an *unauthenticated* request to an unknown path returns 401,
not 404.

### Auth model

Tokens travel as **httpOnly cookies** (`cp_access_token`,
`cp_refresh_token`), never in a response body — the frontend deliberately
keeps no token in JS-reachable storage, so an XSS bug cannot steal a
session. `SameSite=Lax` is what makes disabling Spring's CSRF tokens safe;
re-enable them if any cookie ever becomes `SameSite=None`.

An `Authorization: Bearer` header is also accepted, for service-to-service
calls and for curl/Postman, neither of which has a cookie jar.

**Sessions are tracked server-side.** Every token carries `sid` (the login
session) and every refresh token a `jti` (that individual token), with a row
per issued refresh token in `refresh_tokens`. That buys three things a purely
stateless scheme cannot have:

- **Real sign-out.** Logout revokes the session rather than only clearing the
  browser's cookies, so a token copied elsewhere dies immediately instead of
  living out its remaining 7 days.
- **Reuse detection.** Refresh tokens are single-use. Presenting one that has
  already been rotated means the legitimate client cannot be the one holding
  it, so the whole session is revoked. A ~30 s grace window keeps an honest
  two-tab refresh race from tripping it.
- **Sign out everywhere** (`POST /auth/logout-all`), the control to reach for
  when an account may be compromised.

Access tokens are checked against an **in-memory** blocklist
(`SessionRevocationRegistry`) rather than a per-request database read, so a
revoked session stops working within seconds instead of at the next expiry.
⚠️ That map is per-JVM: **this is correct for one instance only.** Run two
replicas and a sign-out on one leaves the other's access tokens live for up to
their remaining lifetime — the refresh side is still enforced everywhere, so
the gap is bounded by `jwt.access-expiration`, not open-ended. Scaling out
means backing it with Redis or accepting that bound deliberately.
`LoginRateLimiter` carries the same caveat: N replicas allow N times the budget.

**Login is rate limited** — 5 failures per email and 50 per IP in 15 minutes,
then a 15-minute lockout returned as `429 RATE_LIMITED` with `Retry-After`.
BCrypt only makes a *stolen hash* expensive to attack; it does nothing about
guessing over HTTP, which is what this covers. Behind a reverse proxy, set
`FORWARD_HEADERS_STRATEGY=framework` or every user shares the proxy's address
in the per-IP budget.

### Email verification and password reset

> **Read this before changing anything here.**
>
> - **There is no OTP.** No codes are generated, stored, or entered anywhere.
>   Both flows are **links** — the user clicks a URL, never types a number.
> - **Verification gates nothing.** Registration, login, and complaint
>   submission do not check it. `AuthService.authenticate` compares email and
>   password hash and nothing else. A brand-new, unverified account can sign in
>   and file reports immediately.
> - **Email sending is stubbed.** `EMAIL_PROVIDER` defaults to `log`, so
>   `LoggingEmailSender` prints the message and its link to the console and
>   `ResendEmailSender` is never even instantiated. **No provider is configured
>   and none is needed** — both flows work end to end on a fresh clone.
>
> Auth therefore has **zero runtime dependency on email**. If someone asks you
> to "remove the OTP gate" so users can register and sign in without
> verification, that is already the behaviour; there is nothing to remove.

Both flows mail a single-use secret and store only its **SHA-256** — never the
token itself. A reset token *is* the account until it is spent, so a readable
copy of `auth_tokens` would otherwise be account takeover for every pending
reset. (SHA-256 rather than BCrypt is right here precisely because it is wrong
for passwords: the token is 256 bits from a CSPRNG, so there is no dictionary
for a slow KDF to defend against.)

Issuing a link retires the user's previous one of the same purpose, so an older
message left in an inbox stops being a way in. Redemption checks the token's
**purpose**, so a verification link — much easier to obtain — cannot be spent on
a reset. Every failure gives one message, because "expired" rather than "never
existed" would confirm to a stranger that a token they hold was genuine.

`POST /auth/forgot-password` **always answers 204**, and the send is
asynchronous so response time does not leak the answer either. Completing a
reset revokes every session (`PASSWORD_RESET`): people reset precisely because
someone else may be signed in, and leaving that session alive would defeat it.

**Verification is recorded, not enforced.** `user.emailVerified` is exposed for
the UI to nudge with; nothing server-side gates on it. Filing a complaint is
already open and anonymous, so making an account *harder* to use than no account
would push people to the anonymous path — and a hard gate turns any mail failure
into a permanently locked account. Tightening it later is a policy change in
`EmailVerificationService`, not a redesign.

Delivery goes through `EmailSender`, chosen by `app.email.provider` exactly as
the geocoding provider is. It defaults to **`log`**, which prints the message and
its link to the console — so both flows are exercisable end to end on a fresh
clone with no Resend account. Never run `log` in production: it would put live
reset links in the log aggregator. Set `EMAIL_PROVIDER=resend` and
`RESEND_API_KEY` for real delivery.

Resend is called from **Spring, not backend-node**, despite Node being the
TypeScript service Resend publishes an SDK for: auth belongs to this service,
and a round trip through Node would split one flow across two deployables and
put a second service on the critical path for issuing a credential. The SDK is a
thin wrapper over a single JSON POST.

What this deliberately does **not** cover: MFA, and rate limiting on
registration itself — so account spam is still possible.

Full details and error codes: [`docs/api-contract.md`](../docs/api-contract.md).

---

## Database

Flyway owns the schema; Hibernate only validates it.

- Migrations: `src/main/resources/db/migration/V*__*.sql`
- `V1` also runs `CREATE EXTENSION IF NOT EXISTS vector`, so an empty Neon
  database is fully provisioned by the migrations alone.
- `ddl-auto: validate` — Hibernate creates nothing and fails startup if the
  entities have drifted from the migrations. `./mvnw verify` is what catches
  that drift.
- **Never** edit an applied migration — Flyway stores a checksum and will
  refuse to start. Add a new `V*__*.sql`.
- backend-node never runs DDL. A column it needs is added here — that is how
  `V6` added `complaints.embedding vector(1536)`, which only Node writes.

Phase 2 added two migrations worth knowing about:

| | |
|---|---|
| `V6__phase2_matching.sql` | `complaints.embedding vector(1536)`, `matching_status`, `matched_at`, and the `incident_match_log` table (every matching decision, for the Phase 8 evaluation) |
| `V7__match_log_incident_refs_no_fk.sql` | Drops `incident_match_log`'s FKs to `incidents`. The reconcile job deletes an incident once its last complaint moves off it, so those references are historical — a FK would either block that cleanup or erase the naive-vs-semantic disagreement the log exists to record |
| `V8__match_log_created_flag.sql` | Adds `incident_match_log.created`. `decision` cannot express it: `RECONCILED` takes precedence when a complaint moves, so a reconcile into a *new* incident and one into an *existing* incident both record `RECONCILED` — and that join-vs-split distinction is the majority of the rows and exactly what Phase 8 measures |

The vector dimension is fixed at **1536** to match Gemini
`gemini-embedding-001` at `outputDimensionality: 1536`. Changing it means
re-embedding every complaint, so it is a deliberate decision, not a knob.

Reset the schema (Neon Console → SQL Editor), then restart this service:

```sql
DROP SCHEMA public CASCADE; CREATE SCHEMA public;
```

Prefer doing that on a **Neon branch** rather than shared data — see
**Branches** in the root README.

### Seeding demo data

```bash
node scripts/seed-dev-data.mjs            # create ~24 incidents across all wards
node scripts/seed-dev-data.mjs --rescore  # only re-derive priority, no new rows
```

Dev only — deliberately a script rather than a Flyway migration, because a
migration would carry demo content into every environment including production.

It drives the real API rather than inserting rows, so reference numbers, ward
derivation, grouping, and the priority reasons all come from production code
paths instead of a JavaScript reimplementation that would drift.

**Run `backend-node` alongside it** (with `EMBEDDING_API_KEY` set) for real
semantic grouping. Matching is asynchronous, so the script submits everything
first, then waits for the pipeline to settle before it can tell which incidents
formed. Without Node it still works — the naive fallback groups them, tagged
`degraded` — but you are seeding the old baseline, not the matcher.

Expect ~150 embedding calls for a full seed, and expect roughly a third of the
hotspots to **split into two incidents**. That is almost always the concurrency
race rather than the threshold: the script submits far faster than the matcher
drains, so several reports about one problem are embedded in parallel, none sees
a committed sibling, and each starts its own incident. A reconcile pass
consolidates them.

Measured on a clean run (148 complaints, threshold 0.75): 10 of 24 hotspots
split, and **not one** of them was caused by a sub-threshold score — every
decision that had a candidate at all scored ≥ 0.75 and joined. The split rows
are all `candidate_count = 0`. `incident_match_log` is what tells the two apart:

| symptom | cause |
|---|---|
| `candidate_count = 0` | the race — no sibling embedding was visible yet |
| `top_similarity < threshold` | genuinely dissimilar wording |

The one thing the API cannot produce is **age**: everything it creates is new,
so the age term of the priority formula would be zero everywhere and the whole
queue would score the same. The script backdates `created_at` in SQL, then calls
`POST /internal/incidents/{id}/recompute` per incident to re-derive scores
against those ages — which is why it needs `INTERNAL_TOKEN` set.

It deliberately does **not** replay `/internal/incidents/attach` for that.
attach records a matching *decision* — it stamps `matching_status` and appends
an `incident_match_log` row — so using it as a recompute hook would invent
"semantic match" events that never happened and corrupt the dataset Phase 8
measures precision/recall on.

`--rescore` is useful on its own: the age term is time-dependent, so stored
scores drift with no writes at all. It does what a scheduled refresh would.

### Creating an officer

Self-service registration always creates a **citizen** — there is no public
path to an officer account, and no seeded credentials in the repo. Register
normally, then promote in the Neon SQL Editor (or any `psql`):

```sql
UPDATE users SET role='OFFICER' WHERE email='you@example.com';
```

The change takes effect on the user's next login (the role is baked into
the token).

---

## Layout

```
src/main/java/com/civicpulse/backend_spring/
├── config/          SecurityConfig, JwtAuthenticationFilter, CORS, typed+validated
│                    properties, security error handlers, AsyncConfig
│                    (matchingExecutor + @EnableScheduling),
│                    InternalTokenAuthorizationManager (guards /internal/*)
├── controller/      Health, Auth, Ward, Department, Complaint, Incident,
│                    Dashboard, Internal
├── dto/             request/response shapes — controllers NEVER return entities
│   ├── Wire.java    the wire contract in one place: string ids, lowercase
│   │                enums, ISO-8601 UTC timestamps with a trailing Z
│   └── {auth,ward,department,complaint,incident,dashboard,internal}/
├── entity/          JPA entities (must match the migrations), incl. IncidentMatchLog,
│                    RefreshToken (one row per issued refresh token) and
│                    AuthToken (emailed verification/reset secrets, hashed)
├── enums/           UserRole, ComplaintStatus, IncidentStatus, MatchingStatus,
│                    Matcher, MatchOutcome, RevocationReason, AuthTokenPurpose
├── event/           ComplaintCreatedEvent
├── exception/       ApiError (shared wire shape), ErrorCode,
│                    GlobalExceptionHandler, ValidationException,
│                    TooManyAttemptsException (the login lockout, 429)
├── job/             MatchReconciliationJob — @Scheduled sweep over
│                    pending/degraded/stale-processing complaints
│                    AuthCleanupJob — hourly prune of expired refresh tokens
│                    and the two in-memory auth maps
├── listener/        ComplaintCreatedListener — AFTER_COMMIT, fires the async trigger
├── repository/      Spring Data JPA repositories
│   ├── projection/  interface projections for grouped counts
│   └── spec/        IncidentSpecifications — composable optional filters
├── service/
│   ├── auth/        AuthService, JwtService, AuthCookieFactory,
│   │                RefreshTokenService (rotation + reuse detection),
│   │                SessionRevocationRegistry (in-memory, single-instance),
│   │                AuthRateLimiter (sign-in + email-send budgets),
│   │                AuthTokenService (hashed single-use emailed secrets),
│   │                EmailVerificationService, PasswordResetService
│   ├── email/       EmailSender + Resend/Logging implementations,
│   │                AuthEmailComposer (async, builds the two messages)
│   ├── complaint/   ComplaintService, ReferenceNumberService
│   ├── dashboard/   DashboardService
│   ├── incident/    IncidentService, IncidentAttachmentService, MatchDecision,
│   │                NaiveIncidentGrouper (the fallback matcher, config-gated)
│   ├── matching/    ComplaintMatchingService, NodeMatchingClient,
│   │                MatchingCircuitBreaker, Node{Connection,Slow}Exception
│   ├── priority/    PriorityService, PriorityBand, PriorityResult
│   └── ward/        WardService, WardResolver, CentroidWardResolver
└── util/            GeoDistance (haversine; one impl, three callers)
```

**Controllers return DTOs, never entities.** An entity carries the password
hash and lazy associations that would leak or fail during serialisation.
`UserDto` also does the two conversions the frontend contract requires:
ids as strings, enums lowercased.

---

## Conventions

- Every error response uses the shared shape
  `{"error":{"code","message"}}` — see `ErrorCode` for the vocabulary.
  4xx may explain the caller's mistake; 5xx is opaque and logged instead.
- Login failures are identical for unknown email and wrong password —
  distinguishing them enables account enumeration.
- Emails are normalised (trimmed, lowercased) before storage and lookup.
- No secret is ever logged. `JWT_SECRET` and DB credentials appear only in
  `.env`, which is gitignored.
