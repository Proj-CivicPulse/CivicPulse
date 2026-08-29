# Service Boundary Decisions

All three Phase-0 questions are now **decided**. They were blocking: without
them the schema had no owner and Spring/Node ownership drifts under deadline
pressure (Risk Watchlist #4).

> **Status:** proposed and implemented during Phase 0 scaffolding.
> Ratify at the next standup — if the team disagrees with any of these, the
> change is cheap *now* and expensive after Phase 2.

The three decisions reinforce one another:

> **backend-spring is the single writer and the single schema owner.
> backend-node is the intelligence layer that decides *what* should be
> written, and asks Spring to write it.**

---

## 1. Who owns priority calculation?

- **Tension:** it's deterministic rule-based logic (fits Spring Boot's
  style) but is triggered by incident membership that the Node service
  computes (fits Node).
- **Decision:** **backend-spring owns it.**
- **Owner:** `backend-spring`
- **Consumed by:** the officer dashboard (Spring's own APIs) and the
  Copilot (Node reads the stored score/reasons; it never computes them).

**Rationale.** Every input to the formula — complaint volume, growth rate,
incident age, geographic spread — is already a SQL query over tables Spring
owns. Nothing about it needs an embedding or an LLM. Making Node compute it
would mean shipping the incident's complaint set across the wire just to
send a number back. Spring recomputes the score inline whenever an
incident's membership changes (see decision 2), so the score and its
`priority_reasons` are always written in the same transaction as the change
that caused them.

**Consequence:** the explainability strings (`incidents.priority_reasons`,
`jsonb`) are produced by Spring. That is the project's headline
explainability claim — keep the reason text human-readable, not codes.

---

## 2. Who writes to the `Incident` table, and how does `incident_id` get written back to `Complaint`?

- **Options considered:** synchronous call vs. a queue/event.
- **Decision:** **backend-spring performs all writes, called synchronously
  by backend-node.**

**Flow:**

1. A complaint is created (Spring, `POST /complaints`).
2. Spring calls Node: `POST /complaints/{id}/process`.
3. Node generates the embedding, pre-filters candidates
   (ward + category + active), runs similarity, and **decides**: join
   incident *X*, or start a new one.
4. Node calls back: `POST /internal/incidents/attach` with its decision.
5. Spring, in **one transaction**: creates or loads the incident, sets
   `complaints.incident_id`, updates `complaint_count`, and recomputes
   `priority_score` + `priority_reasons` (decision 1).

**Rationale.** A single writer removes the whole class of races and
double-write bugs two services would otherwise create on the same rows, and
it means `incident_id` write-back is not a separate step that can fail on
its own — it is part of the same transaction.

**Why synchronous, not a queue:** no queue exists in the stack, and the
roadmap explicitly defers real-time/eventing infrastructure for the MVP.
Adding one now buys nothing except operational surface. Revisit if
embedding latency starts blocking complaint submission — the natural next
step is to make step 2 async, not to add a broker between 4 and 5.

**Requirement this creates:** Spring→Node (step 2) needs a timeout and a
retry policy, or a slow Node will stall complaint submission. Tracked as an
open question in [`api-contract.md`](api-contract.md).

---

## 3. Who owns migrations for shared tables?

- **Goal:** avoid both services freely modifying the same tables.
- **Decision:** **backend-spring owns every migration, via Flyway.**
- **Implemented:** `backend-spring/src/main/resources/db/migration/`

**Rules:**

- Spring runs `ddl-auto: validate`, never `update`. Hibernate no longer
  creates or alters anything; it only fails startup if the JPA entities
  have drifted from what the migrations produced. The context test
  (`BackendSpringApplicationTests`) is what catches that drift in CI.
- **backend-node never runs DDL.** It reads and writes rows through `pg`,
  and its queries are always parameterized (`$1, $2`).
- When Node needs a schema change, it goes in as a new `V*__*.sql` in
  backend-spring — including the **Phase 2 pgvector column**. The `vector`
  extension itself is created by `V1__init_schema.sql`, so an empty Neon
  database is provisioned entirely by the migrations; the column
  (`ALTER TABLE complaints ADD COLUMN embedding vector(<dim>)`) waits until
  the embedding provider — and therefore the dimension — is chosen.

**Rationale.** Two services running migrations against one database is the
single most reliable way to corrupt a shared schema. One owner, one ordered
history, one place to look.

---

_Update this file if a decision changes, and link the PR that implements it._
