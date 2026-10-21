# Moving the Database to Railway

**Why:** on 2026-10-20 the Neon free plan ran out of compute ("Your account or
project has exceeded the quota"), which took the whole app down. The backend's
scheduled jobs query the database every minute, so a Neon free database never
gets to sleep and burns its monthly allowance. A Railway Postgres service runs
continuously and is billed by resource use on the Hobby plan instead — no
monthly compute cap to hit.

**What carries over:** the schema, all 243 wards and the category registry
rebuild themselves — Spring's Flyway migrations (V1–V21) run on first start.
Verified on 2026-10-21 against the image below: all 21 migrations apply to an
empty database, and the full test suite passes on it (229 Spring, 49 Node).

**What does not:** the old complaints, incidents and user accounts were not
carried over. They were seed data; step 6 recreates them.

---

## 1. The database image

`infra/postgres/Dockerfile` — PostgreSQL 16 + pgvector + PostGIS. Both
extensions are required (V1 creates `vector`, V13 creates `postgis`) and no
stock image ships both. Railway's default Postgres template will NOT work: the
migrations stop at V1 or V13.

## 2. Create the service

In the Railway project that already holds backend-spring and backend-node:

1. **New → GitHub Repo** → this repository. In the service's **Settings**:
   - **Root Directory:** `infra/postgres` (Railway builds the Dockerfile there)
   - Name it, e.g. `civicpulse-db` (the name becomes its private host name)
2. **Variables** on that service:

   | Variable | Value |
   |---|---|
   | `POSTGRES_USER` | `civicpulse` |
   | `POSTGRES_PASSWORD` | a long random string |
   | `POSTGRES_DB` | `civicpulse` |
   | `PGDATA` | `/var/lib/postgresql/data/pgdata` |

   `PGDATA` points at a sub-folder because a fresh volume contains a
   `lost+found` directory, and Postgres refuses to initialise a non-empty one.
3. **Volume:** add one mounted at `/var/lib/postgresql/data`. Without it, every
   redeploy starts from an empty database.
4. Do **not** add a public domain or TCP proxy unless you need to connect from a
   laptop (step 6). The app reaches it over Railway's private network.

## 3. Point the backends at it

Railway's private network host is `<service-name>.railway.internal`. No
`sslmode` — that traffic never leaves Railway's network, and
`sslmode=require` would fail against this server (it does not offer SSL).

**backend-spring** variables:

| Variable | Value |
|---|---|
| `DB_URL` | `jdbc:postgresql://civicpulse-db.railway.internal:5432/civicpulse` |
| `DB_USERNAME` | `civicpulse` |
| `DB_PASSWORD` | the password from step 2 |
| `DB_MIGRATION_URL`, `DB_MIGRATION_USERNAME`, `DB_MIGRATION_PASSWORD` | leave **unset** — only for a transaction-pooling host; set, they point Flyway elsewhere |

**backend-node** variable:

| Variable | Value |
|---|---|
| `DATABASE_URL` | `postgres://civicpulse:<password>@civicpulse-db.railway.internal:5432/civicpulse` |

Railway's reference variables can fill these in for you (e.g.
`${{civicpulse-db.POSTGRES_PASSWORD}}`), so the password lives in one place.

## 4. Deploy, in order

1. The database service (wait until it is running).
2. **backend-spring** — on start it runs V1–V21 and loads the wards. Watch the
   log for `Successfully applied 21 migrations … now at version v21`.
3. **backend-node.**
4. The frontend needs nothing: it talks to the backends, not the database.

Check: `https://<spring-domain>/health` returns `{"status":"ok"}` and the
landing page's ward strip loads.

## 5. Recreate an officer

Accounts did not move. Register through the site (registration always creates a
citizen), then promote the account — see "Creating an officer" in
`backend-spring/README.md`. To run that SQL, use the database service's
**Data** tab in Railway, or connect with a temporary TCP proxy.

## 6. Seed demo data

The data before the move was itself this seed, so re-running it recreates the
same kind of dataset — now with one real problem per hotspot. From the repo root, after `railway link` and
`railway ssh keys add`:

```bash
SEED_API=https://<spring-domain> SEED_DB=railway node backend-spring/scripts/seed-dev-data.mjs
```

- `SEED_DB=railway` runs the script's SQL (wait for matching, backdate) through
  `railway ssh` inside the database service — no TCP proxy, no password.
- `SEED_WARDS` (default 6) wards, spread across the city: ~230 complaints.
- `SEED_INTERNAL_TOKEN` (the deployed `INTERNAL_TOKEN`) rescores priorities at
  once; without it the script marks them stale and Spring's 5-minute
  PriorityRefreshJob does it.
- Grouping uses backend-node's Gemini key: one embedding per complaint, a
  fraction of a rupee for the whole seed.

**Running SQL by hand** (no Data tab on a custom-image service):

```bash
railway ssh -s civicpulse-db -- psql -U civicpulse -d civicpulse -c "select count(*) from complaints"
```

## 7. Tests never touch this database

Since 2026-10-21, `TEST_DB_*` moves both the test connection and Flyway, so the
suite can run against a throwaway local database instead of anything deployed:

```bash
# once: build the same image
docker build -t civicpulse-postgres:local infra/postgres
docker run -d --name civicpulse-pg-test -e POSTGRES_PASSWORD=localtest \
  -e POSTGRES_DB=civicpulse -p 55432:5432 civicpulse-postgres:local

# backend-spring
TEST_DB_URL=jdbc:postgresql://localhost:55432/civicpulse \
TEST_DB_USERNAME=postgres TEST_DB_PASSWORD=localtest ./mvnw test

# backend-node (enables the real-pgvector tests)
TEST_DATABASE_URL=postgres://postgres:localtest@localhost:55432/civicpulse npm test
```

Without `TEST_DB_URL` the suite still uses `DB_URL` — i.e. whatever your
`.env` points at. Keep `.env` pointed at a local or test database, never at
Railway's production one.
