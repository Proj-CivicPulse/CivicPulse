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
| Embeddings | External embedding API | Converts complaint text into comparable vectors |
| LLM | External LLM API | Powers summaries and the grounded Copilot |

**Deferred until actually needed:** PostGIS (only if ward-boundary lookups
become a real blocker), real-time WebSocket updates (polling is sufficient
for MVP), Python/scikit-learn predictive hotspots (only if Phase 7 is
triggered by sufficient historical data).

### Service boundaries

- **Spring Boot owns:** auth, users, complaint CRUD, officer CRUD, dashboard
  APIs
- **Node.js owns:** embedding generation, incident matching, Copilot/LLM
  orchestration
- Open ownership questions (priority calculation, `Incident` table writes,
  shared-table migrations) are tracked and resolved in
  [`docs/service-boundaries.md`](docs/service-boundaries.md) — see that file
  before touching shared tables.

---

## Getting started

### Prerequisites

- Java (**latest LTS** — e.g. 21) and Maven/Gradle (for `backend-spring`)
- Node.js (**latest LTS** — e.g. 22) for `backend-node` and `frontend`
- Python (**latest LTS/stable** — e.g. 3.12) for `ml-hotspots`, if/when Phase 7 is triggered
- **Docker Desktop** — runs local PostgreSQL + pgvector via
  `docker-compose.yml` (no local Postgres install needed). A native
  PostgreSQL 15+ with `pgvector` also works if you prefer.
- An API key for the embedding provider and LLM provider (see `.env.example`
  in each service)

> **Version policy:** this project uses **LTS versions only** for all
> runtimes (Java, Node.js, Python). Do not use bleeding-edge/current
> releases — check the LTS schedule before upgrading anything.

### Local setup

```bash
# Clone the repo
git clone https://github.com/Proj-CivicPulse/CivicPulse.git
cd CivicPulse

# 1. Database — PostgreSQL 17 + pgvector in Docker (see next section)
docker compose up -d postgres

# 2. Spring Boot service
cd backend-spring
cp .env.example .env   # fill in DB credentials, JWT secret
./mvnw spring-boot:run

# 3. Node.js AI service
cd ../backend-node
cp .env.example .env   # DATABASE_URL already points at the Docker DB
npm install
npm run dev

# 4. React frontend
cd ../frontend
npm install
npm run dev
```

Each service's own `README.md` (inside its folder) has service-specific
details once those folders are scaffolded.

---

## Database (local, via Docker)

`docker-compose.yml` at the repo root runs **PostgreSQL 17 with the
`pgvector` extension** (`pgvector/pgvector:pg17`). The `vector` extension is
created automatically on first startup by
`docker/postgres/initdb/01-enable-vector.sql`.

Defaults (database `civicpulse`, user `civicpulse`, password `civicpulse`,
port `5432`) match `backend-node/.env`, so
`DATABASE_URL=postgres://civicpulse:civicpulse@localhost:5432/civicpulse`
works unchanged. These are **local-only throwaway credentials** — to change
them, `cp .env.example .env` at the repo root and edit (that `.env` is
gitignored).

### First-time setup

1. Install **[Docker Desktop for Windows](https://www.docker.com/products/docker-desktop/)**
   (WSL 2 backend — the installer enables it; if it complains, run
   `wsl --install` from an elevated PowerShell and reboot).
2. **Launch Docker Desktop and wait for it to say "Engine running"**
   (whale icon in the system tray, not animating). The Docker CLI talks to
   this background engine — if it isn't running you get:
   `failed to connect to the docker API at npipe:////./pipe/docker_engine`.
   Tip: Docker Desktop → Settings → General → *Start Docker Desktop when
   you sign in*.
3. Verify the engine is reachable — `docker version` must show a **Server**
   section, not just Client:
   ```powershell
   docker version
   docker run --rm hello-world
   ```
4. From the repo root, start the database:
   ```powershell
   docker compose up -d postgres
   ```
   The first run pulls `pgvector/pgvector:pg17` from Docker Hub (~150 MB,
   one-time, needs internet) and initializes the data volume. Watch it come
   up healthy with `docker compose ps` (`STATUS` → `healthy`).

That's the whole setup for anyone cloning the repo — no local PostgreSQL
install, no manual database or user creation, credentials already match
`backend-node/.env.example`.

### Commands

All commands are run **from the repo root**, in PowerShell or Windows
Terminal, with **Docker Desktop running**:

| Task | Command |
|---|---|
| Start (detached) | `docker compose up -d postgres` |
| Stop (keep data) | `docker compose stop postgres` |
| Restart | `docker compose restart postgres` |
| Status / health | `docker compose ps` |
| Follow logs | `docker compose logs -f postgres` |
| Open `psql` | `docker compose exec postgres psql -U civicpulse -d civicpulse` |
| Verify pgvector | `docker compose exec postgres psql -U civicpulse -d civicpulse -c "\dx vector"` |
| Full reset (delete container **and** data volume) | `docker compose down -v` |
| Stop + remove container, keep data | `docker compose down` |

Notes:

- **Safe to rerun.** `docker compose up -d` is idempotent; the init SQL uses
  `CREATE EXTENSION IF NOT EXISTS`.
- The init script runs **only on first initialization** (empty data
  volume). After a `docker compose down` (without `-v`) the existing volume
  is reused and the script does **not** re-run — it doesn't need to, the
  extension is already there. If you ever start from a volume that predates
  this setup, enable it once by hand:
  `docker compose exec postgres psql -U civicpulse -d civicpulse -c "CREATE EXTENSION IF NOT EXISTS vector;"`
- Port `5432` is published on `127.0.0.1` only. If it's already in use
  (e.g. a native Postgres service), stop that service or set `POSTGRES_PORT`
  in the root `.env` and update `DATABASE_URL` in `backend-node/.env` to
  match.
- No schema/migrations are included — the repo has none yet (Phase 0).

**Troubleshooting**

| Symptom | Fix |
|---|---|
| `failed to connect to the docker API at npipe:////./pipe/docker_engine` | Docker Desktop isn't running. Launch it, wait for "Engine running". |
| `ports are not available: exposing port TCP 127.0.0.1:5432` / `bind: ... forbidden by its access permissions` | Something else already owns port 5432 — usually a **native PostgreSQL Windows service**. Either stop it (`Stop-Service postgresql-x64-18; Set-Service postgresql-x64-18 -StartupType Manual` in an elevated PowerShell), **or** keep both: put `POSTGRES_PORT=5433` in the repo-root `.env` and change `DATABASE_URL` in `backend-node/.env` to `...@localhost:5433/...`. Find the culprit with `netstat -ano \| findstr :5432`. |
| Docker Desktop won't start / "virtualization" error | Enable virtualization (VT-x / AMD-V / SVM) in BIOS/UEFI; run `wsl --update`. |
| `docker compose` → "command not found" but `docker` works | Old standalone Compose. Use `docker-compose` (hyphen), or update Docker Desktop (v2 bundles `docker compose`). |
| `no such service: #` (or `'#' is not recognized`) | You pasted a trailing `# comment` into **cmd.exe**, where `#` isn't a comment. Run the command without it, or use PowerShell. |
| Pull fails with an auth/rate-limit error | `docker login` with a free Docker Hub account, then retry. |
| `psql: FATAL: role "civicpulse" does not exist` | Data volume was created before this setup. `docker compose down -v` then `up -d` to reinitialize (destroys local data). |

---

## Project status

Following a phased roadmap (see [`docs/roadmap.md`](docs/roadmap.md) for the
full detail):

- [ ] Phase 0 — Foundations (repo, schema, auth skeleton, service-boundary
      decisions)
- [ ] Phase 1 — Complaint ingestion
- [ ] Phase 2 — Embeddings + incident matching
- [ ] Phase 3 — Explainable priority engine
- [ ] Phase 4 — Officer dashboard + map
- [ ] Phase 5 — Trends & analytics
- [ ] Phase 6 — Officer Copilot
- [ ] Phase 7 — Predictive hotspots (conditional on data quality)
- [ ] Phase 8 — Evaluation + paper

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
