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
> Complaint-to-incident grouping is a **placeholder** (ward + category + a
> 14-day window + a 1.5 km radius) until Phase 2 semantic matching lands in
> backend-node. It is config-gated on `INCIDENT_GROUPING_STRATEGY` and every
> decision is logged, so it can be measured and switched off. It is not
> semantic matching and is not presented as such.

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
[root README](../README.md). On first run Flyway provisions everything:
the `pgvector` extension, all five tables, and seed wards/departments.

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
| `INTERNAL_TOKEN` | for Node | *(blank)* | Shared secret for `/internal/*`. **Blank denies everything** — an unset secret breaks the integration rather than opening it. Also required by the dev seed script below. |
| `INCIDENT_GROUPING_STRATEGY` | no | `naive` | `naive` or `none`. Set `none` to leave incident assignment entirely to Node. |
| `INCIDENT_GROUPING_WINDOW_DAYS` | no | `14` | Only join incidents opened within this window. |
| `INCIDENT_GROUPING_MAX_KM` | no | `1.5` | Only join incidents whose centroid is within this radius. |
| `GEOCODING_PROVIDER` | no | `none` | `none` or `google`. Default needs no key and no billing account — addresses simply stay null. |
| `GOOGLE_GEOCODING_API_KEY` | for geocoding | *(blank)* | **Server-side only.** Never reaches the browser, so restrict it by **IP address**, not HTTP referrer. |
| `HTTP_CONNECT_TIMEOUT_MS` | no | `3000` | Bound on outbound third-party calls. |
| `HTTP_READ_TIMEOUT_MS` | no | `5000` | As above. An untimed client pins a request thread when the provider is slow. |

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
| `POST /auth/login` | public | **live** — sets cookies, returns `{user}` |
| `GET /auth/me` | cookie | **live** — session restore |
| `POST /auth/refresh` | refresh cookie | **live** — rotates the pair |
| `POST /auth/logout` | public | **live** — clears cookies, 204 |
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
| `POST /internal/incidents/attach` | `X-Internal-Token` | **live** — Node decides, Spring writes |
| `GET /internal/complaints/{id}`, `GET /internal/wards/{id}` | `X-Internal-Token` | **live** |
| `/analytics/**` | `officer` | Phase 5 |

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
- backend-node never runs DDL. A column it needs (e.g. the Phase 2 pgvector
  `embedding`) is added here.

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

The one thing the API cannot produce is **age**: everything it creates is new,
so the age term of the priority formula would be zero everywhere and the whole
queue would score the same. The script backdates `created_at` in SQL, then
replays `POST /internal/incidents/attach` per incident to recompute against
those ages — which is why it needs `INTERNAL_TOKEN` set.

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
│                    properties, security error handlers,
│                    InternalTokenAuthorizationManager (guards /internal/*)
├── controller/      Health, Auth, Ward, Department, Complaint, Incident,
│                    Dashboard, Internal
├── dto/             request/response shapes — controllers NEVER return entities
│   ├── Wire.java    the wire contract in one place: string ids, lowercase
│   │                enums, ISO-8601 UTC timestamps with a trailing Z
│   └── {auth,ward,department,complaint,incident,dashboard,internal}/
├── entity/          JPA entities (must match the migrations)
├── enums/           UserRole, ComplaintStatus, IncidentStatus
├── exception/       ApiError (shared wire shape), ErrorCode,
│                    GlobalExceptionHandler, ValidationException
├── repository/      Spring Data JPA repositories
│   ├── projection/  interface projections for grouped counts
│   └── spec/        IncidentSpecifications — composable optional filters
├── service/
│   ├── auth/        AuthService, JwtService, AuthCookieFactory
│   ├── complaint/   ComplaintService, ReferenceNumberService
│   ├── dashboard/   DashboardService
│   ├── incident/    IncidentService, IncidentAttachmentService,
│   │                NaiveIncidentGrouper (placeholder, config-gated)
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
