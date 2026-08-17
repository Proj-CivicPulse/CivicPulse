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

- Java 17+ and Maven/Gradle (for `backend-spring`)
- Node.js 18+ (for `backend-node` and `frontend`)
- PostgreSQL 15+ with the `pgvector` extension enabled
- An API key for the embedding provider and LLM provider (see `.env.example`
  in each service)

### Local setup

```bash
# Clone the repo
git clone <TODO: repo-url>
cd civicpulse

# 1. Database
createdb civicpulse
psql civicpulse -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 2. Spring Boot service
cd backend-spring
cp .env.example .env   # fill in DB credentials, JWT secret
./mvnw spring-boot:run

# 3. Node.js AI service
cd ../backend-node
cp .env.example .env   # fill in DB credentials, embedding/LLM API keys
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
| TODO: Name 1 | TODO: e.g. Backend (Spring Boot) | TODO: @handle |
| TODO: Name 2 | TODO: e.g. AI Layer (Node.js) | TODO: @handle |
| TODO: Name 3 | TODO: e.g. Frontend (React) | TODO: @handle |
| TODO: Name 4 | TODO: e.g. ML / Evaluation | TODO: @handle |

**Course / Institution:** TODO
**Advisor / Instructor:** TODO
**Submission deadline:** TODO

---

## License

This project is licensed under the [MIT License](LICENSE).

TODO: confirm this is acceptable under your course/institution's IP policy
before publishing the repo publicly. If there's any chance of
commercialization or the policy is unclear, hold off on adding a LICENSE
file (or keep the repo private) until confirmed with your advisor.
