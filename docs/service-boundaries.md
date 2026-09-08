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

The matching side has its own analytics record, `incident_match_log`, also
Spring-written (in the attach transaction): every join/create/reconcile with
its similarity score, candidate count, threshold, and — on a reconcile move —
the incident it came from. Node computes those numbers and passes them in the
attach call; it never writes the table. This is the dataset Phase 8 evaluates
the matcher on.

---

## 2. Who writes to the `Incident` table, and how does `incident_id` get written back to `Complaint`?

- **Options considered:** synchronous call vs. a queue/event.
- **Decision:** **backend-spring performs all incident writes. The trigger to
  backend-node is asynchronous** (fired after the complaint commits), with a
  circuit breaker, a naive fallback, and a reconcile sweep behind it.

> Originally this trigger was synchronous, on the basis that "the natural next
> step, if embedding latency starts blocking submission, is to make step 2
> async." Phase 2 took that step up front — an embedding call is 100–1000 ms and
> the citizen should never wait on it. Single-writer (the part that actually
> prevents races) is unchanged.

**Flow:**

1. A complaint is created (Spring, `POST /complaints`) and committed. The
   response goes back immediately with `matchingStatus: "pending"`.
2. `AFTER_COMMIT`, a listener fires the async trigger on a bounded executor:
   `POST {node}/complaints/{id}/process`.
3. Node **claims** the complaint with an atomic compare-and-swap to
   `PROCESSING`, generates the embedding, pre-filters candidates
   (ward + category + active), runs single-linkage similarity, and **decides**:
   join incident *X*, or start a new one.
4. Node calls back: `POST /internal/incidents/attach` with its decision +
   score context.
5. Spring, in **one transaction**: creates or loads the incident, sets
   `complaints.incident_id`, `matching_status`, `matched_at`, `complaint_count`,
   recomputes `priority_score` + `priority_reasons` (decision 1), and writes one
   `incident_match_log` row (the Phase 8 dataset).

**When Node cannot answer:**

- **Connection failure / 5xx** → `MatchingCircuitBreaker.recordFailure()`; after
  N in a row the breaker opens and new triggers skip Node entirely for a
  cooldown. The complaint falls back to `NaiveIncidentGrouper` (ward + category
  + window + radius) and is tagged `matching_status = DEGRADED`.
- **Timeout** → Node may still be working (it took the `PROCESSING` claim before
  doing anything slow). Spring re-reads the status: `PROCESSING`/`MATCHED` means
  leave it alone, the real result is coming; still `PENDING` means the request
  never landed — treat as a failure and fall back.
- **`MatchReconciliationJob`** (`@Scheduled`) sweeps `PENDING` + `DEGRADED` +
  stale-`PROCESSING` complaints once the circuit is closed again, re-runs the
  real pipeline, and moves any naive mis-grouping to the right incident
  (a `RECONCILED` log row, and the emptied naive incident is deleted).

**Rationale.** A single writer removes the whole class of races and
double-write bugs two services would otherwise create on the same rows, and
it means `incident_id` write-back is not a separate step that can fail on
its own — it is part of the same transaction. Async delivery of the *trigger*
doesn't touch that: whoever decided the match, Spring still performs the write.

### Concurrency model — read this before it looks like a bug

- **Node keeps computing after Spring's 2.5 s client timeout, and its late
  callback to `/internal/incidents/attach` is intentional.** The
  `matchingRestClient` read timeout is shorter than Node's embedding timeout, so
  a slow provider means Spring stops listening while Node works on. Node
  finishes and still POSTs the attach; the attach path folds the late result in
  cleanly (records `previous_incident_id`, recomputes/deletes the superseded
  incident). Spring does **not** fall back to naive in this window — it sees the
  `PROCESSING` claim and defers.
- **The `PROCESSING` compare-and-swap claim is the single in-flight guard.** A
  second `/process` call for the same complaint (the reconcile sweep firing
  while the first trigger is still working, a manual retry) cannot take the
  claim and returns `{ "skipped": true }`. This is the only thing preventing two
  Node runs from both calling attach.
- **Who writes `matching_status`:** Node owns `PENDING ⇄ PROCESSING` (the claim,
  and the release back to the prior status on a failed run) and
  `complaints.embedding`; Spring owns the terminal `MATCHED` / `DEGRADED` writes
  and everything on `incidents` and `incident_match_log`.
- **The naive fallback never clobbers a semantic result.** `IncidentAttachmentService`
  ignores a `NAIVE` decision when the complaint is already `MATCHED` or
  `PROCESSING` — checked inside the write transaction, so it is immune to the gap
  between the fallback's status read and its attach call.
- **`processing-stale-seconds` is duplicated** in `app.matching.processing-stale-seconds`
  (Spring) and `PROCESSING_STALE_SECONDS` (Node). Node's CAS predicate is the
  authority on whether a stale claim can be re-taken; Spring's copy only
  pre-filters which rows the reconcile sweep offers. Drift between the two costs
  at most a wasted `skipped` round-trip — keep them equal anyway.
- **Simultaneous reports of the same problem can each start an incident.** The
  matching executor runs several complaints at once, and a candidate is only
  visible once its embedding is committed — so two reports filed seconds apart
  may each find no candidate and create their own incident. This is inherent to
  incremental clustering done concurrently, and it affects the naive fallback
  equally. It is **self-healing**: the reconcile sweep processes complaints
  serially, so the second one then scores against the first and merges, and the
  emptied incident is deleted (observed live — two reports of one burst pipe
  split across incidents 60/61, merged to 62 at 0.947 on the next pass). Do not
  "fix" it by serialising the executor; that would trade a transient,
  self-correcting duplicate for a permanent throughput ceiling. The officer
  manual-merge override is the operational backstop for anything reconciliation
  does not catch.

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
  database is provisioned entirely by the migrations. Phase 2 added the column
  in `V6__phase2_matching.sql` as `vector(1536)` — Gemini `gemini-embedding-001`
  at `outputDimensionality: 1536`. `V7` drops the `incident_match_log` foreign
  keys to `incidents` (the reconcile job deletes emptied incidents, so those
  references are historical, not live).

**Rationale.** Two services running migrations against one database is the
single most reliable way to corrupt a shared schema. One owner, one ordered
history, one place to look.

---

_Update this file if a decision changes, and link the PR that implements it._
