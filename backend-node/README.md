# backend-node

CivicPulse's **AI / matching service**. Owns (per
[`docs/service-boundaries.md`](../docs/service-boundaries.md)):

- embedding generation
- incident matching (semantic grouping of complaints)
- Copilot / LLM orchestration

Everything transactional — auth, users, complaint/officer CRUD, dashboard
APIs — belongs to `backend-spring`, not here.

> **Status:** Phase 0 scaffold. Only `GET /health` is functional. All
> AI/matching/Copilot routes are stubs returning `501 NOT_IMPLEMENTED`
> until their phase (2 / 6 / 7) and the open decisions in
> `docs/service-boundaries.md` land.

---

## Stack

| Concern | Choice |
|---|---|
| Runtime | Node.js 22 LTS (project policy: LTS only) |
| Language | TypeScript, `strict`, ESM (`"type": "module"` + `NodeNext`) |
| HTTP | Express 5 (async errors auto-forward — no per-route try/catch) |
| DB | `pg` (node-postgres) with pooling; pgvector-ready |
| Validation | `zod` — env vars **and** every request body |
| Logging | `pino` + `pino-http` (structured, non-blocking) |
| Dev / build | `tsx` (watch) / `tsc` |
| Lint | `oxlint` |

**Imports:** source files use explicit `.ts` extensions on relative
imports (`./config/env.ts`). `tsx` resolves them directly in dev; `tsc`
rewrites them to `.js` on build (`rewriteRelativeImportExtensions`) so the
compiled `dist/` runs under plain `node`.

---

## Setup

```bash
cd backend-node
cp .env.example .env      # paste your Neon DATABASE_URL
npm install
npm run dev               # tsx watch, hot reload on :3001
```

Requires a reachable PostgreSQL — the project uses **Neon**; see
**Database (Neon)** in the [root README](../README.md). `GET /health`
reports `error` / 503 until the database actually answers.

**Run `backend-spring` at least once first.** It owns every migration and
creates the schema (and the `pgvector` extension) on a fresh database. This
service never runs DDL.

`DATABASE_URL` is a **libpq** URL (`postgres://user:pass@host/db?sslmode=require`).
backend-spring's `DB_URL` is a **JDBC** URL with credentials as separate
properties — the two are not interchangeable.

### Scripts

| Script | Does |
|---|---|
| `npm run dev` | `tsx watch src/index.ts` — hot reload |
| `npm run build` | `tsc` → `dist/` |
| `npm start` | `node dist/index.js` (run `build` first) |
| `npm run typecheck` | `tsc --noEmit` |
| `npm run lint` | `oxlint` |

---

## Environment

All of these are validated on startup by
[`src/config/env.ts`](src/config/env.ts). A missing or malformed value
exits the process with a message naming the variable and the rule it broke
— **never** its value.

| Var | Required | Default | Notes |
|---|---|---|---|
| `PORT` | no | `3001` | Frontend proxy forwards `/api/ai/*` here |
| `NODE_ENV` | no | `development` | `development` \| `production` \| `test` |
| `DATABASE_URL` | **yes** | — | Neon libpq URL; `sslmode=require` is mandatory |
| `FRONTEND_ORIGIN` | **yes** | — | Single CORS origin, no wildcards |
| `HEALTHCHECK_TIMEOUT_MS` | no | `10000` | Ceiling on the `/health` probe. Sized for a Neon cold start; lower for a local DB. |
| `LOG_LEVEL` | no | `info` | pino level |

---

## Routes

Routes are **prefix-free** here. The `/api/ai` prefix is a frontend
routing convention added by the Vite dev proxy
([`frontend/vite.config.ts`](../frontend/vite.config.ts)) / the prod
reverse proxy — it must never appear in this service's own route
definitions.

| Method & path | Status | Phase |
|---|---|---|
| `GET /health` | **live** — real `SELECT 1`; `200 {status:'ok'}` or `503 {status:'error'}` | 0 |
| `POST /complaints/:id/process` | `501` stub | 2 |
| `POST /copilot/query` | `501` stub (validates body first → `400`) | 6 |
| `GET /hotspots/predict` | `501` stub | 7 (conditional) |

### Error shape

Every error response matches
[`docs/api-contract.md`](../docs/api-contract.md) exactly:

```json
{ "error": { "code": "UPPER_SNAKE_CASE", "message": "human readable" } }
```

Codes in use: `VALIDATION_ERROR` (400), `INVALID_JSON` (400),
`PAYLOAD_TOO_LARGE` (413), `UNSUPPORTED_MEDIA_TYPE` (415), `NOT_FOUND` (404),
`RATE_LIMITED` (429), `NOT_IMPLEMENTED` (501), `INTERNAL_ERROR` (500).
Stack traces and internal details are logged server-side only, never sent
to the client.

---

## Layout

```
src/
├── index.ts              server bootstrap + graceful shutdown only
├── app.ts                express app assembly (no listen — testable)
├── config/
│   ├── env.ts            zod-validated env, fail-fast on startup
│   └── logger.ts         pino instance
├── db/
│   └── pool.ts           pg Pool + checkDatabase() for /health
├── middleware/
│   ├── errorHandler.ts   HttpError class, 404 handler, central error handler
│   └── rateLimiter.ts    general per-IP limiter
├── routes/
│   ├── health.route.ts
│   ├── complaints.route.ts
│   ├── copilot.route.ts
│   └── hotspots.route.ts
└── services/             Phase 2/6 shape only — no logic yet
    ├── embedding.service.ts
    ├── matching.service.ts
    └── copilot.service.ts
```

## Security & performance notes

- `helmet()` on every response (`contentSecurityPolicy` off — JSON-only API).
- CORS locked to `FRONTEND_ORIGIN`, `credentials: true`, never `*`.
- `express.json({ limit: '100kb' })` caps payload size.
- `app.set('trust proxy', 1)` so rate limiting / `req.ip` see the real client.
- Per-IP rate limit (100 / 15 min); `/health` exempt. `POST /copilot/query`
  gets its own strict limiter in Phase 6.
- Graceful shutdown on `SIGTERM`/`SIGINT`: drain in-flight requests, close
  the pool, 10s force-exit safety net.
- **All future SQL must be parameterized (`$1, $2`).** Never
  string-concatenate SQL. This is reinforced in comments where queries will
  live (`db/pool.ts`, `services/*`).
