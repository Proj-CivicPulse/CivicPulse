# CivicPulse

> TODO: add badges here once CI is set up, e.g. build status, license badge
> `![Build](TODO)` `![License: MIT](TODO)`

CivicPulse is a civic-complaint management platform that turns raw citizen
complaints into **grouped, prioritized, explainable incidents** for city
officers — using semantic matching (embeddings + pgvector) instead of naive
keyword or category grouping.

Citizens submit complaints (category, location, description, photo).
CivicPulse automatically matches related complaints into a single
**Incident**, computes a transparent priority score, and gives officers a
dashboard + AI Copilot to act on what matters most.

---

## Why CivicPulse

Cities receive many complaints about the same underlying problem (a pothole,
a broken streetlight, garbage overflow) filed independently by different
citizens, in different words. Without semantic grouping, officers see noise
instead of incidents. CivicPulse:

- **Matches** semantically similar complaints into incidents using vector
  embeddings, not just category/ward filters
- **Prioritizes** incidents with a rule-based, explainable formula — every
  priority score comes with human-readable reasons, not a black box
- **Surfaces** trends and hotspots so officers can act proactively, not just
  reactively
- **Answers questions** via a grounded Copilot that only responds from
  CivicPulse's own retrieved data — no hallucinated city information

---

## Architecture

CivicPulse is a **monorepo** with three services, split so that stable
transactional logic and fast-iterating AI logic can evolve independently:

```
civicpulse/
├── backend-spring/     # Core APIs: auth, users, complaints, officers, dashboard
├── backend-node/       # AI layer: embeddings, incident matching, Copilot/LLM
├── frontend/           # React app: citizen portal + officer dashboard
└── docs/                # Architecture decisions, API contracts, schema notes
```

| Layer | Choice | Reason |
|---|---|---|
| Frontend | React | Shared component patterns across citizen portal and officer dashboard |
| Core backend | Spring Boot | Stable transactional business logic (auth, CRUD, dashboard APIs) |
| AI backend | Node.js | Fast-iterating embedding/matching/LLM-orchestration layer |
| Database | PostgreSQL | System of record; Complaint/Incident/Ward/Department are inherently relational |
| Vector search | pgvector | Core requirement for semantic incident matching |
| Maps | Leaflet + OpenStreetMap | Free, lightweight, sufficient for markers and hotspot display |
| Auth | JWT | Role-based access control for citizens vs. officers |
| Embeddings | Google Gemini `gemini-embedding-001` (1536-dim) | Converts complaint text into comparable vectors. Chosen for **multilingual** coverage — complaint text here mixes English, Hindi and Kannada, and one model handles all three |
| LLM | External LLM API | Powers summaries and the grounded Copilot |

**Deferred until actually needed:** PostGIS (only if ward-boundary lookups
become a real blocker), real-time WebSocket updates (polling is sufficient
for MVP), Python/scikit-learn predictive hotspots (only if Phase 7 is
triggered by sufficient historical data).

### Service boundaries

> **Spring Boot is the single writer and the single schema owner. Node.js is
> the intelligence layer that decides *what* should be written.**

- **Spring Boot owns:** auth, users, complaint CRUD, officer CRUD, dashboard
  APIs, priority calculation, all `Incident` writes, **and every database
  migration**
- **Node.js owns:** embedding generation, incident matching, Copilot/LLM
  orchestration. It never runs DDL.
- The Phase-0 ownership questions are **resolved** in
  [`docs/service-boundaries.md`](docs/service-boundaries.md) — read it before
  touching shared tables. The cross-service wire format is in
  [`docs/api-contract.md`](docs/api-contract.md).

**The matching handoff is asynchronous.** `POST /complaints` commits and returns
straight away with `matchingStatus: "pending"` — a citizen never waits on an
embedding call. Spring then triggers Node in the background; Node embeds,
matches, and asks Spring to write the result. If Node is slow or down, a circuit
breaker falls back to naive ward+category grouping (tagged `degraded`) and a
reconciliation job re-runs the real pipeline once Node recovers. The full
concurrency model — including the in-flight claim that stops two Node runs
racing — is decision 2 in `service-boundaries.md`.

---

## Getting started

### Prerequisites

- Java (**latest LTS** — e.g. 21) for `backend-spring`. No Maven install
  needed — the repo ships the Maven wrapper (`./mvnw`).
- Node.js (**latest LTS** — e.g. 22) for `backend-node` and `frontend`
- Python (**latest LTS/stable** — e.g. 3.12) for `ml-hotspots`, if/when Phase 7 is triggered
- A **[Neon](https://neon.com)** account — the project uses Neon's hosted
  serverless PostgreSQL with `pgvector`. Nothing to install locally.
- A **Google AI Studio API key** for embeddings
  ([aistudio.google.com/apikey](https://aistudio.google.com/apikey)) — free tier
  is enough for development. This is a *different* key from the optional Google
  geocoding key, which lives in `backend-spring`. An LLM key is only needed from
  Phase 6. See `.env.example` in each service.

> **Version policy:** this project uses **LTS versions only** for all
> runtimes (Java, Node.js, Python). Do not use bleeding-edge/current
> releases — check the LTS schedule before upgrading anything.

### Local setup

```bash
# Clone the repo
git clone https://github.com/Proj-CivicPulse/CivicPulse.git
cd CivicPulse

# 1. Database — nothing to install. Create a Neon project and copy your
#    connection string (see the next section).

# 2. Spring Boot service (core API). Applies the schema migrations on boot.
cd backend-spring
cp .env.example .env   # Neon DB_URL; JWT_SECRET (32+ chars); INTERNAL_TOKEN
./mvnw spring-boot:run # no local Maven install needed

# 3. Node.js AI service
cd ../backend-node
cp .env.example .env   # Neon DATABASE_URL; the SAME INTERNAL_TOKEN; EMBEDDING_API_KEY
npm install
npm run dev

# 4. React frontend
cd ../frontend
cp .env.example .env.local   # nothing in it is required to boot
npm install
npm run dev                  # http://localhost:5173
```

`INTERNAL_TOKEN` must be **identical in both backends** — it authenticates
service-to-service calls in both directions, and each side fails closed when it
is blank. Generate one with `openssl rand -hex 32`.

### Seeing something on the screen

A fresh database has wards and departments but no complaints, so the officer
dashboard is legitimately empty. Two more steps:

```bash
# Demo data. Run backend-node alongside it for real semantic grouping;
# without it the naive fallback groups them and tags them "degraded".
cd backend-spring && node scripts/seed-dev-data.mjs
```

Then give yourself an officer account. Registration **always** creates a
citizen — there is no public path to an officer, and no seeded credentials in
the repo. Sign up normally, then promote yourself in the Neon SQL Editor:

```sql
UPDATE users SET role='OFFICER' WHERE email='you@example.com';
```

Log out and back in; the role is baked into the token. Citizens land on
`/complaints`, officers on `/dashboard`.

One thing that surprises people: a complaint you have just submitted shows
`matchingStatus: "pending"` with no incident. That is correct — matching runs
*after* the response so you are never kept waiting on an embedding call. It
becomes `matched` a second or two later.

Each service's own `README.md` (inside its folder) has service-specific details:
[`backend-spring`](backend-spring/README.md) ·
[`backend-node`](backend-node/README.md) ·
[`frontend`](frontend/README.md).

---

## Database (Neon)

The project uses **[Neon](https://neon.com)** — hosted serverless PostgreSQL
with `pgvector`. There is nothing to install locally, and everyone on the
team can share one database or take their own branch of it.

**The schema is not created by hand.** `backend-spring` owns every
migration (Flyway) and applies them on startup — including
`CREATE EXTENSION vector`. A brand-new, empty Neon database is fully
provisioned the first time you run the Spring service.

### First-time setup

1. Create a free account at [neon.com](https://neon.com) and create a
   project. Pick a region near you.
2. From the project dashboard, copy the **connection string**. Neon shows
   two; use the **direct** one — the host *without* `-pooler` in it. Both
   backends run their own connection pool, so Neon's PgBouncer pooler would
   just be a second pool in front. (Switch to `-pooler` only for a
   serverless deployment.)
3. Put it in each service's `.env` — note the two formats differ:

   | Service | Var | Format |
   |---|---|---|
   | `backend-spring` | `DB_URL` + `DB_USERNAME` + `DB_PASSWORD` | **JDBC**: `jdbc:postgresql://HOST/neondb?sslmode=require`, credentials as separate properties, no `channel_binding` |
   | `backend-node` | `DATABASE_URL` | **libpq**: `postgres://USER:PASS@HOST/neondb?sslmode=require` |

   Both `.env.example` files spell this out. `sslmode=require` is mandatory —
   Neon refuses plaintext connections.
4. Start `backend-spring` once (`./mvnw spring-boot:run`). Flyway creates the
   `pgvector` extension, every table, and seeds reference wards/departments.

### Working with the database

Use the **SQL Editor** in the Neon Console for ad-hoc queries — no `psql`
install required. If you do want a local client:

```bash
psql "postgres://USER:PASS@HOST/neondb?sslmode=require"
```

| Task | How |
|---|---|
| Inspect data | Neon Console → SQL Editor, or `psql` with the connection string |
| Apply new migrations | Add `V*__*.sql` in `backend-spring/src/main/resources/db/migration/`, restart the service |
| Reset the schema | Neon Console → SQL Editor → `DROP SCHEMA public CASCADE; CREATE SCHEMA public;` then restart `backend-spring` |
| Give yourself an isolated copy | Neon Console → Branches → **New branch** (see below) |

### Branches — use them

Neon branches are copy-on-write clones: instant, and free on the starter
plan. This is the main reason Neon suits a team project.

- **Per developer.** Branch off `main`, point your `.env` at it, and you can
  break things without affecting anyone else.
- **For tests.** `./mvnw verify` is a real integration test — it runs the
  migrations against whatever your `.env` points at. Aim it at a throwaway
  branch with `TEST_DB_URL` / `TEST_DB_USERNAME` / `TEST_DB_PASSWORD`
  (see `backend-spring/src/test/resources/application-test.yaml`) rather
  than at shared data.

### Things to know

- **The compute suspends when idle** (a few minutes on the free plan). The
  next request wakes it, which takes a second or two. Both services size
  their timeouts for this — `HEALTH_TIMEOUT_SECONDS` / `HEALTHCHECK_TIMEOUT_MS`
  default to 10s, and connection timeouts to 15s. A local database can use
  much tighter values.
- **Never commit a connection string.** Neon credentials live only in
  `.env` files, which are gitignored. If one is ever exposed, rotate it:
  Neon Console → your project → **Roles** → *Reset password*.
- **Free-tier limits** are compute-hours and storage. Leaving a service
  running with an idle connection pool keeps the compute awake, so both
  backends deliberately release idle connections.

**Troubleshooting**

| Symptom | Fix |
|---|---|
| `password authentication failed` | Password was rotated or mistyped. Copy a fresh connection string from the Neon Console. |
| `no pg_hba.conf entry ... no encryption` | `sslmode=require` is missing from the URL. |
| JDBC: `The connection attempt failed` / unknown parameter | You pasted the libpq URL into `DB_URL`. It must start `jdbc:postgresql://`, carry no credentials, and drop `channel_binding`. |
| First request after a break is slow, or `/health` returns `error` once | Neon compute waking from suspend. Retry; if it persists, raise `HEALTH_TIMEOUT_SECONDS`. |
| `relation "users" does not exist` | Migrations have not run. Start `backend-spring` once. |
| Flyway: `Migration checksum mismatch` | An already-applied migration file was edited. Never edit one — add a new `V*__*.sql` (or reset the schema on a scratch branch). |

---

## Project status

Following a phased roadmap (see [`docs/roadmap.md`](docs/roadmap.md) for the
full detail):

- [x] Phase 0 — Foundations. Running skeleton: Neon Postgres + pgvector,
      Flyway-owned schema, JWT auth (cookie-based) in Spring, both services
      health-checked against the database and reachable through the frontend
      proxy, service-boundary decisions written down.
- [x] Phase 1 — Complaint ingestion. Public anonymous submission, ward derived
      from coordinates, gap-free reference numbers allocated in-transaction,
      optional server-side reverse geocoding, officer/citizen read paths.
- [x] Phase 2 — Embeddings + incident matching. Gemini `gemini-embedding-001`
      (1536-dim, L2-normalised) into pgvector; ward+category+status candidate
      pre-filter then single-linkage cosine similarity; async trigger with a
      circuit breaker, naive fallback, and a reconciliation sweep. Every
      decision — semantic, naive, and reconcile move — is recorded in
      `incident_match_log` with its scores, which is the dataset Phase 8
      evaluates precision/recall/F1 on.
- [x] Phase 3 — Explainable priority engine. Rule-based score over volume,
      growth, age and geographic spread, stored with the human-readable
      reasons, recomputed in the same transaction as any membership change.
- [x] Phase 4 — Officer dashboard + map. Ward rail → incident queue → incident
      detail drill-down, Leaflet markers coloured by priority band.
- [ ] Phase 5 — Trends & analytics
- [ ] Phase 6 — Officer Copilot
- [ ] Phase 7 — Predictive hotspots (conditional on data quality)
- [ ] Phase 8 — Evaluation + paper

Next up is **threshold tuning**: `MATCH_SIMILARITY_THRESHOLD` ships at `0.75`
as a starting point, and `incident_match_log` now has the raw scores needed to
choose a better one against labelled data.

---

## Contributing

This is a private group project repo. Workflow for contributors:

1. Branch off `main`: `git checkout -b feature/TODO-short-description`
2. Commit with clear messages
3. Open a PR against `main` — TODO: confirm required number of reviewers
4. TODO: add link to issue tracker / project board (e.g. GitHub Projects,
   Jira, Trello) once set up

---

## Team

| Name | Role | GitHub |
|---|---|---|
| Suryansh | Backend — Spring Boot | TODO: @handle |
| Abhishek | Backend — Node.js (AI layer) | TODO: @handle |
| Devansh | UI/UX — design | TODO: @handle |
| Manya | ML / Python | TODO: @handle |
| Ria | TODO — not yet decided | TODO: @handle |
| Alaafiya | TODO — not yet decided | TODO: @handle |

---

## License

This project is licensed under the [MIT License](LICENSE).
