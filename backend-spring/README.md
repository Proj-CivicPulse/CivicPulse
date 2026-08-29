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

> **Status:** Phase 0. Auth and the schema are live. Complaint, incident,
> dashboard, and `/internal/*` endpoints are not built yet.

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
| `POST /complaints` | public | Phase 1 |
| `GET /complaints/mine` | `citizen` | Phase 1 |
| `/incidents/**`, `/dashboard/**`, `/analytics/**` | `officer` | Phase 3–5 |

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
├── config/          SecurityConfig, JwtAuthenticationFilter, CORS,
│                    typed+validated properties, security error handlers
├── controller/      HealthController, AuthController
├── dto/             request/response shapes — controllers NEVER return entities
├── entity/          JPA entities (must match the migrations)
├── enums/           UserRole, ComplaintStatus, IncidentStatus
├── exception/       ApiError (shared wire shape), ErrorCode, GlobalExceptionHandler
├── repository/      Spring Data JPA repositories
└── service/auth/    AuthService, JwtService, AuthCookieFactory
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
