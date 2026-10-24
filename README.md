# CivicPulse

CivicPulse is a civic-complaint management platform that turns raw citizen
complaints into **grouped, prioritized, explainable incidents** for city
officers — using semantic matching (embeddings + pgvector) instead of naive
keyword or category grouping.

Citizens submit complaints (category, location, description). CivicPulse
automatically matches related complaints into a single **Incident**, computes a
transparent priority score, and gives officers a dashboard to act on what
matters most. Photo upload is planned, not shipped — see the phase list at
the end.

---

## Why CivicPulse

Cities receive many complaints about the same underlying problem (a pothole,
a broken streetlight, garbage overflow) filed independently by different
citizens, in different words. Without semantic grouping, officers see noise
instead of incidents. CivicPulse:

- **Matches** semantically similar complaints into incidents using vector
  embeddings. Ward, category and status are hard pre-filters: meaning decides
  the match *within* a category, never across one — see
  [docs/matching-contract.md](docs/matching-contract.md)
- **Knows where things are.** 243 real BBMP ward boundaries with PostGIS
  point-in-polygon, not nearest-centroid guesswork — see
  [docs/ward-data.md](docs/ward-data.md)
- **Prioritizes** incidents with a rule-based, explainable formula — every
  priority score comes with human-readable reasons, not a black box
- **Surfaces** trends, hotspots and a "needs attention" list so officers can
  act proactively, not just reactively, and flags life-safety hazards from
  report text for the officer to confirm
- **Answers questions** via a grounded Copilot that responds only from
  CivicPulse's own data, citing an incident, ward or category for every claim
  — no hallucinated city information

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

**Promoted from deferred:** PostGIS. It was held back until ward-boundary
lookups became a real blocker — and they did: nearest-centroid assignment
disagreed with containment near every irregular ward boundary, and ward is a
hard constraint on matching, so a wrong ward silently prevents related reports
from grouping. V13 imports 243 real BBMP boundaries and
`PostGisWardResolver` is now the default.

**Still deferred until actually needed:** real-time WebSocket updates (polling
is sufficient). Hotspots are rule-based (PostGIS clustering), not a trained
model: there is no historical data to train one on.

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
- **Docker Desktop** — the local database is PostgreSQL 16 + `pgvector` +
  PostGIS, built from [`infra/postgres`](infra/postgres/Dockerfile).
- A **Google AI Studio API key** for embeddings
  ([aistudio.google.com/apikey](https://aistudio.google.com/apikey)). Billed per
  call, but complaint embeddings cost a fraction of a rupee each. This is a
  *different* key from the optional Google geocoding key, which lives in
  `backend-spring`. The officer Copilot runs on a free local model (Ollama) or
  a billed one — see `COPILOT_PROVIDER` in `backend-node/.env.example`.

> **Version policy:** this project uses **LTS versions only** for all
> runtimes (Java, Node.js). Do not use bleeding-edge/current
> releases — check the LTS schedule before upgrading anything.

### Local setup

```bash
# Clone the repo
git clone https://github.com/Proj-CivicPulse/CivicPulse.git
cd CivicPulse

# 1. Database — a local container, once (see "Database" below).
docker build -t civicpulse-postgres:local infra/postgres
docker run -d --name civicpulse-pg --restart unless-stopped -p 55432:5432 \
  -e POSTGRES_PASSWORD=localdev -e POSTGRES_DB=civicpulse \
  -v civicpulse-pgdata:/var/lib/postgresql/data civicpulse-postgres:local

# 2. Spring Boot service (core API). Applies the schema migrations on boot.
cd backend-spring
cp .env.example .env   # DB_* already match the container; JWT_SECRET (32+ chars); INTERNAL_TOKEN
./mvnw spring-boot:run # no local Maven install needed

# 3. Node.js AI service
cd ../backend-node
cp .env.example .env   # DATABASE_URL already matches; the SAME INTERNAL_TOKEN; EMBEDDING_API_KEY
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

**No email provider is needed to run this.** Sign-up and sign-in have no
dependency on email at all: registration signs you straight in, and nothing —
not login, not complaint submission — is gated on a verified address. There is
no OTP anywhere in the project. Email verification and password reset do exist,
but they send **links**, and `EMAIL_PROVIDER` defaults to `log`, which prints
the message and its link to the Spring console instead of delivering it. Both
flows are therefore fully usable on a fresh clone with no account and no API
key. See [backend-spring/README.md](backend-spring/README.md#email-verification-and-password-reset).

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
the repo. Sign up normally, then promote yourself:

```bash
docker exec civicpulse-pg psql -U postgres -d civicpulse \
  -c "UPDATE users SET role='OFFICER' WHERE email='you@example.com';"
```

(On the deployed database, the same `psql` command runs through
`railway ssh` — see [docs/railway-database.md](docs/railway-database.md).)

Log out and back in; the role is baked into the token. Citizens land on
`/complaints`, officers on `/dashboard`.

One thing that surprises people: a complaint you have just submitted shows
`matchingStatus: "pending"` with no incident. That is correct — matching runs
*after* the response so you are never kept waiting on an embedding call. It
becomes `matched` a second or two later.

### Photos locally (optional)

Photo upload needs an S3-compatible bucket; without one it is simply off. A
local one in two commands:

```bash
echo '{"identities":[{"name":"dev","credentials":[{"accessKey":"localphotos","secretKey":"localphotos-secret"}],"actions":["Admin","Read","Write","List"]}]}' > s3.json
docker run -d --name civicpulse-s3 -p 8333:8333 -v "$PWD/s3.json:/etc/s3.json" \
  chrislusf/seaweedfs:4.48 server -s3 -s3.port=8333 -s3.config=/etc/s3.json -dir=/data
docker exec civicpulse-s3 sh -c 'echo "s3.bucket.create -name civicpulse-photos" | weed shell'
```

Then in `backend-spring/.env`: `PHOTO_STORAGE_ENDPOINT=http://localhost:8333`,
`PHOTO_STORAGE_BUCKET=civicpulse-photos`, `PHOTO_STORAGE_ACCESS_KEY=localphotos`,
`PHOTO_STORAGE_SECRET_KEY=localphotos-secret`, `PHOTO_STORAGE_REGION=us-east-1`,
`PHOTO_STORAGE_PATH_STYLE=true`; and in `frontend/.env.local`:
`VITE_PHOTO_STORAGE_ORIGIN=http://localhost:8333`. Restart both.

Each service's own `README.md` (inside its folder) has service-specific details:
[`backend-spring`](backend-spring/README.md) ·
[`backend-node`](backend-node/README.md) ·
[`frontend`](frontend/README.md).

---

## Database

PostgreSQL 16 with two extensions: `pgvector` (complaint embeddings) and
PostGIS (ward polygons). No stock image ships both, so the repo builds one:
[`infra/postgres/Dockerfile`](infra/postgres/Dockerfile).

- **Locally:** that image in Docker (the commands in *Local setup* above), on
  port 55432 so it never collides with another Postgres.
- **Production:** the same image as a Railway service, reached over Railway's
  private network — [docs/railway-database.md](docs/railway-database.md).
  (Until 2026-10-20 production ran on Neon; its free compute quota ran out
  because the backend's scheduled sweeps never let it suspend.)

**The schema is not created by hand.** `backend-spring` owns every migration
(Flyway) and applies them on startup — including both extensions, all 243
wards and the category registry. A brand-new, empty database is fully
provisioned the first time you run the Spring service.

| Service | Var | Format |
|---|---|---|
| `backend-spring` | `DB_URL` + `DB_USERNAME` + `DB_PASSWORD` | **JDBC**: `jdbc:postgresql://localhost:55432/civicpulse`, credentials as separate properties |
| `backend-node` | `DATABASE_URL` | **libpq**: `postgres://postgres:localdev@localhost:55432/civicpulse` |

### Working with the database

| Task | How |
|---|---|
| Ad-hoc SQL, locally | `docker exec -it civicpulse-pg psql -U postgres -d civicpulse` |
| Ad-hoc SQL, production | `railway ssh -s civicpulse-db -- psql -U civicpulse -d civicpulse -c "…"` |
| Apply new migrations | Add `V*__*.sql` in `backend-spring/src/main/resources/db/migration/`, restart the service |
| Reset your local schema | `docker rm -f civicpulse-pg && docker volume rm civicpulse-pgdata`, re-run the `docker run`, restart `backend-spring` |
| Demo data | `node backend-spring/scripts/seed-dev-data.mjs` (*Seeing something on the screen*) |

### Things to know

- **Never point a local `.env` at production.** Every Spring start and every
  `./mvnw test` runs Flyway against `DB_URL`. For tests, `TEST_DB_URL` /
  `TEST_DB_USERNAME` / `TEST_DB_PASSWORD` move both the test connection and
  Flyway to a throwaway database (docs/railway-database.md §7).
- **Never commit a connection string.** Credentials live only in `.env`
  files, which are gitignored.

**Troubleshooting**

| Symptom | Fix |
|---|---|
| `Connection refused` on 55432 | The container is not running: `docker start civicpulse-pg` (Docker Desktop must be up). |
| `extension "postgis" is not available` | The database is a stock Postgres image. Use the one built from `infra/postgres`. |
| JDBC: `The connection attempt failed` / unknown parameter | You pasted the libpq URL into `DB_URL`. It must start `jdbc:postgresql://` and carry no credentials. |
| `relation "users" does not exist` | Migrations have not run. Start `backend-spring` once. |
| Flyway: `Migration checksum mismatch` | An already-applied migration file was edited. Never edit one — add a new `V*__*.sql` (or reset your local schema). |

---

## Project status

**[docs/roadmap.md](docs/roadmap.md) is the single list of what is done and
what is left.** In short (2026-10-25):

- [x] 0–5 — Foundations, complaint ingestion, embeddings + incident matching,
      explainable priority, officer dashboard + map, trends
- [x] Stabilisation + data foundation — category registry, 243 real BBMP
      wards, ingestion gate, dataset validation
- [x] 6 — Explorer: wards, categories and incidents on one shared filter state
- [x] 7 — Matching evaluation (threshold set to 0.84)
- [x] 8 — Priority calibration (proxy run; age now saturates at 30 days)
- [x] 9 — Hotspots → 10 — "Needs attention" → 11 — Decision support
- [x] Life-safety flag, officer merge/unlink, "why grouped", `timestamptz`
- [x] 12 — Officer Copilot — on every Explorer page; eval 21/22; sees hotspots and life-safety flags
- [ ] 13 — Paper (if required) + backup demo video

---

## Contributing

This is a private group project repo. Workflow for contributors:

1. Branch off `main`: `git checkout -b feature/short-description`
2. Commit with clear messages, and keep `docs/` in step with the code
3. Open a PR against `main` and have a teammate review it
4. Run the tests before merging (see "Things to know" above: point `TEST_DB_*`
   at a throwaway database, never production)

---

## Team

| Name | Role |
|---|---|
| Suryansh | Backend — Spring Boot |
| Abhishek | Backend — Node.js (AI layer) |
| Devansh | UI/UX — design |
| Manya | ML / Python; Officer Copilot panel |
| Ria | Team member |
| Alaafiya | Team member |

---

## License

This project is licensed under the [MIT License](LICENSE).
