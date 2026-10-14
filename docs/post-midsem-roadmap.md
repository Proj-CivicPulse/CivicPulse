# CivicPulse — Post-Midsem Master Handoff & Development Roadmap

**Status:** Post-midsem master plan — authoritative version, checked against the
repository and the live database on 2026-10-06
**Purpose:** Move CivicPulse from a stabilized prototype into a coherent, usable
municipal operations intelligence platform
**Primary rule:** Build upward from the verified foundation. Do not repeatedly
redesign working infrastructure without evidence that it is broken.

---

## Corrections applied to the uploaded draft

This document is the uploaded plan with every claim verified against the code,
the migrations and production. Section numbering is unchanged so the two can be
read side by side. What changed:

| § | Draft said | Reality |
|---|---|---|
| 8, 15, 19 | `severity` is a field to filter and rank on | **It does not exist anywhere** — not on `incidents`, not on `categories`, not in any migration. Decided against; `priorityBand` is the single ranking axis. See [priority-evaluation.md](priority-evaluation.md) §DECISION. |
| 11, 13 | Resolution metrics | Were not computable — nothing recorded *when* an incident was resolved. **Now fixed**: `incidents.resolved_at` (V18). Metrics start 2026-10-06; earlier incidents have no resolution time and never will, because inventing one is worse than its absence. |
| 19 | Priority must "remain explainable" | Already is. `incidents.priority_reasons` is a persisted text array written at computation time. |
| 4.4, 10 | "Existing scheduled jobs" | Correct, and worth naming: `PriorityRefreshJob` (5 min), `MatchReconciliationJob`, `AuthCleanupJob`. All enabled by default. |
| 7, 35 | Build an Explorer shell | It is a **refactor**, not a greenfield build. `frontend/src/pages/IncidentDashboard.tsx` and `DashboardController` already exist. |
| 24 | Six idempotency cases to test | Two are satisfied by construction — the hash runs over a flat DTO through a `TreeMap`, so field reordering and unknown metadata cannot change it. The one with a real decision in it is *changed source timestamp*. |
| 31 | Nine doc files to maintain | Seven are renames of docs that already exist. Mapping table added in §31; creating them as new files would fork the documentation. |
| 1 | "`photoUrl` is no longer accepted" | True of `POST /complaints` only. The ingest path still stored an upstream URL verbatim. **Now closed on both paths.** |

---

# 1. Current State

Verified against the repository and the live Neon database on 2026-10-06. Every
line below was re-checked, not carried over.

- Production application and Neon migration state are synchronized.
- Live Flyway head is **V18** (V17 at the time of the draft).
- Repository `main` is clean and synchronized with origin.
- Baseline database is 167 complaints / 31 incidents, all incidents `OPEN`.
- PostGIS is the authoritative ward resolver (`WARD_RESOLVER=postgis` in
  production, verified by probing coordinates where PostGIS and the old centroid
  resolver disagree).
- Out-of-area coordinates return 404.
- Legacy/retired ward assignment was eliminated from the write path.
- `photoUrl` is accepted on **neither** write path: not on `POST /complaints`,
  and no longer carried from `/internal/ingest` into `complaints.photo_url`.
- `created_at` and `reported_at` have separate semantics, and `reported_at` is
  `NOT NULL` with zero nulls and zero divergences in production (correct — no
  feed has run).
- Reconciliation uses arrival order (`order by c.id`) rather than report
  chronology, so a backdated import cannot jump the queue.
- Incident report chronology uses `reported_at`.
- Priority growth calculations use `reported_at`.
- `incidents.resolved_at` records when an incident was resolved, and is cleared
  if it is reopened.
- Dataset validation is persisted through `dataset_validations` — five ward
  checks, four PASS and one WARN (45 wards with no national LGD code).
- Category normalization is canonical and data-driven through `category_aliases`.
  All 167 complaints carry canonical codes; no free-form values survive.
- Unknown categories are rejected at the boundary rather than stored.
- Semantic matching is operational on the tested write path (a probe complaint
  reached `MATCHED`, not the naive fallback).
- Priority computation is operational: 0 of 31 open incidents lack
  `priority_computed_at`.

**One caveat that is not in the draft and should be known.** All timestamp
columns are `timestamp without time zone`, and their UTC-ness is enforced by a
JVM-wide `TimeZone.setDefault` in a static initialiser. That works and is
tested, but it is a process-level guarantee protecting a column-level property:
any other writer to that database is outside it. Moving to `timestamptz` is the
one schema change worth considering, and it touches every timestamp column, so
it is a deliberate piece of work rather than a side effect of something else.

This means CivicPulse should now move from **stabilization** to **product
development**.

---

# 2. New Objective

The objective is no longer:

> Keep CivicPulse from breaking.

It is:

> Build CivicPulse into a trustworthy municipal operations intelligence platform.

```text
Complaint Management
        ↓
Complaint Intelligence
        ↓
Incident Intelligence
        ↓
Operational Intelligence
        ↓
Decision Support
```

The system should help an operator answer:

1. What is happening?
2. Where is it happening?
3. How serious is it?
4. Which complaints belong together?
5. Which incidents need attention first?
6. What is getting worse?
7. What should the operator investigate or act on next?

---

# 3. Core Architecture — Keep This Mental Model

```text
Citizen / External Source
        ↓
Raw Complaint
        ↓
Validation
        ↓
Normalization
        ↓
Canonical Complaint
        ↓
Semantic / Spatial Matching
        ↓
Incident
        ↓
Priority
        ↓
Operational Analytics
        ↓
Explorer / Command Center
```

The important distinction:

```text
Complaint ≠ Incident
```

A complaint is an observation. An incident is the operational entity created by
grouping related observations — it is what gets assigned, prioritised, and
closed. That is why `resolved_at` lives on incidents and not on complaints
(§13).

Explorer should operate primarily around incidents while retaining
complaint-level drill-down.

---

# 4. Development Principles

## 4.1 One Source of Truth

Canonical business concepts have exactly one authoritative implementation:

```text
Category identity      → categories + category_aliases (V11, V14)
Ward assignment        → PostGIS ST_Contains (PostGisWardResolver)
Complaint event time   → complaints.reported_at
Complaint arrival      → complaints.id
Incident grouping      → backend-node matching
Priority + band        → PriorityService / PriorityBand
Resolution time        → incidents.resolved_at
```

Each of those has one writer. Adding a second is the failure this principle
exists to prevent.

## 4.2 API Before UI

```text
Data requirement → Backend query/service → DTO/API contract
    → Frontend state → Visualization
```

The frontend displays backend-defined facts. It does not reconstruct them from
raw records.

## 4.3 Preserve Domain Semantics

- Do not use one timestamp for multiple meanings.
- Do not use raw category strings as canonical identity.
- Do not use centroid distance as the authoritative ward resolver.
- Do not treat every complaint as an independent incident.
- Do not mix source-specific ingestion logic into core domain logic.
- Do not derive resolution time from `updated_at` — see §13.

## 4.4 Prefer Boring Infrastructure

Do not introduce Kafka, Kubernetes, Redis, another vector database, another ORM,
microservices or event sourcing without a demonstrated requirement.

What exists and is sufficient:

```text
Spring Boot 4 + PostgreSQL/PostGIS/pgvector
backend-node matching service
React frontend
Three scheduled jobs: PriorityRefreshJob (5 min),
                      MatchReconciliationJob,
                      AuthCleanupJob (1 h)
```

All three jobs are `@ConditionalOnProperty(matchIfMissing = true)` — on by
default, individually switchable off.

---

# 5. Phase 1 — Close the Data Foundation

## Goal

Finish the work that makes the Explorer trustworthy.

## 5.1 Ward Dataset Audit

**Extend [`ward-data.md`](ward-data.md).** Do not create
`ward-dataset-audit.md` — the existing file already covers source, dataset
version, transformations, the ring-classification problem, the geometry repairs
and the retired placeholders.

What it still needs:

- license, if determinable
- active / retired / placeholder counts stated as numbers
- the overlap check (not currently documented either way)
- LGD mapping coverage, cross-referenced to the `dataset_validations` row

The known warning must stay explicit:

```text
45 wards without an LGD code  →  dataset_validations, status WARN
```

Do not invent mappings. The WARN is a fact about the source: the 2022
delimitation created those wards and the national directory has not issued codes.

## 5.2 Category Dataset Audit

**Create [`category-dataset-audit.md`](category-dataset-audit.md).** This one is
genuinely new.

Document:

- the 8 canonical codes and their current usage counts
- every alias, grouped by canonical code, with its `source` scope
- `footpath` and `noise` — added by V14 because V11's quarantine report found
  them in live data rather than sweeping them into `other`
- what rejection looks like at the boundary, and where rejected values surface
- department ownership, if it is defined anywhere
- deprecated categories (none yet; the `active` flag is the mechanism)

Canonical category values remain the aggregation identity.

## 5.3 Dataset Validation Workflow

`dataset_validations` exists and is populated. What does **not** exist is a
runtime path that writes it: the only two writers today are V17's backfill and
`generate-ward-migration.mjs`. The draft is right that this should not stay
migration-only.

The policy, so that whoever builds the first importer implements it rather than
inventing it:

```text
New dataset → Parse → Validate → Persist results → Gate on worst status

FAIL  → block the import entirely. No partial loads.
WARN  → import permitted ONLY with a documented reason recorded in
        dataset_validations.detail. "Tolerated" must be written down,
        not remembered.
PASS  → normal import.
```

Two rules that are not obvious and were learned the hard way:

- **Re-derive what you can, transcribe what you cannot, and say which.** Four of
  the five ward rows are re-measured from current rows by V17's own SELECTs;
  `repair-area-drift` is transcribed from the V13 run because the pre-repair
  geometry no longer exists, and its `detail` says so.
- **Never regenerate over an applied migration.** Flyway checksums applied
  migrations, so regenerating V13 would not produce a new import — it would break
  startup in every environment that already ran the old one. The generator now
  refuses. A re-import is a new migration, numbered above V18.

---

# 6. Phase 2 — Backend/API Contract Freeze

**Extend [`api-contract.md`](api-contract.md) and
[`endpoints.md`](endpoints.md).** Do not create `explorer-api-contract.md`:
`api-contract.md` already defines every response shape including `Incident`,
`Complaint` and the error envelope, and a second contract document is a second
answer to every question it covers.

For each new or extended endpoint define: method, path, auth, parameters,
request, response DTO, pagination, sorting, filtering, date semantics, empty
behaviour, error behaviour, maximum result size.

## 6.1 Avoid Endpoint Duplication

Ten controllers already exist:

```text
AuthController        ComplaintController    IncidentController
CategoryController    DashboardController    IngestionController
DepartmentController  WardController         InternalController
HealthController
```

Before creating an endpoint: search these, check whether an existing one can
serve the use case, extend it if the domain responsibility is the same, and only
create a new one when it genuinely is not.

Most Explorer aggregation belongs **on `DashboardController`**, which already
serves breakdowns. Do not create:

```text
/wards  /ward-summary  /ward-stats  /ward-analytics  /ward-overview
```

all returning overlapping data because different cards need different fields.

## 6.2 DTOs Over Database Entities

Explorer APIs expose explicit response DTOs. Never raw JPA entities.

```java
WardExplorerSummary {
    wardId; wardCode; wardName;
    complaintCount; activeIncidentCount; highPriorityIncidentCount;
    dominantCategory; lastActivity;
}
```

`highPriorityIncidentCount` means `priorityBand in (high, critical)`. Define it
once, server-side, for the same reason `PriorityBand` lives on the server: three
clients with three private copies of four thresholds are three chances to
disagree about what "high" means.

---

# 7. Phase 3 — Explorer Foundation

## Goal

One reusable Explorer shell rather than three unrelated dashboards.

```text
                 Explorer State
                      │
          ┌───────────┼───────────┐
          ↓           ↓           ↓
        Map         Cards       Table
          │           │           │
          └───────────┼───────────┘
                      ↓
                  API Query
```

**This is a refactor.** `frontend/src/pages/IncidentDashboard.tsx` already
renders an incident queue against `DashboardController`. Build the shell by
lifting its state, not beside it — a second dashboard is the exact duplication
§6.1 warns about, one layer up.

---

# 8. Shared Explorer State

One filter state, owned above the components:

```ts
type ExplorerFilters = {
  wardIds: string[];
  categoryCodes: string[];          // canonical codes, never display names
  priorityBands: PriorityBand[];    // 'low' | 'medium' | 'high' | 'critical'
  statuses: IncidentStatus[];       // 'open' | 'in_progress' | 'resolved' | 'closed'
  from: string | null;              // against reported_at — see below
  to: string | null;
  search: string;
};
```

Three deliberate differences from the draft:

- **No `severities`.** The field does not exist and is not being added; see
  [priority-evaluation.md](priority-evaluation.md) §DECISION. `priorityBands` is
  the ranking filter.
- **`categoryCodes`, not `categoryIds`.** The canonical code is the identity
  every consumer compares on; surfacing the surrogate key would invite a second
  one.
- **`from`/`to` filter `reported_at`**, not `created_at`. An operator asking
  "what came in last week" means when the problems were reported. Decide this
  once, here, or two endpoints will answer it differently.

The same state drives API requests, map, cards, table, charts and filter chips.
No component owns a private copy.

---

# 9. Explorer UX States

Every Explorer page supports: Loading, Loaded, Empty, Error, Retry, Reset
filters.

Distinguish **no matching data** from **the API failed**. They are
operationally different and the fix differs: one is "widen your filters", the
other is "something is broken". A spinner that never resolves says neither.

---

# 10. Explorer Data Freshness

Show, where it is useful, using fields that actually exist:

| Claim | Source |
|---|---|
| Priority last computed | `incidents.priorityComputedAt` (on the DTO) |
| Ward data version | `wards.dataset_version` / `dataset_validations.dataset_version` |
| Last ingestion | `ingestion_batches.finished_at` |
| Last activity in a ward | `max(complaints.reported_at)` |

Do not present a cached aggregate as real-time. `priorityComputedAt` exists
precisely so a ranked queue can admit how old its ranking is.

---

# 11. Phase 4 — Ward Explorer

## Goal

> What is happening across each ward?

### Ward Summary

- total complaints
- active incidents (`open` + `in_progress`)
- high-priority incidents (`priorityBand in (high, critical)`)
- dominant category
- recent activity (`max(reported_at)`)
- resolution state — the `status` breakdown. For resolution *time*, see §13.

### Ward Table

```text
Ward | Complaints | Active incidents | High priority | Dominant category | Last activity
```

### Ward Map

Ward boundaries, selected ward, complaint/incident density, optional priority
overlay.

**Only active wards by default** — `wards.active = true`. The four
`LEGACY-` placeholders retired by V13 are deliberately still in the table so old
rows stay readable, and they must not appear on a map.

---

# 12. Ward Detail

```text
Ward metadata → Incident summary → Category distribution → Trend
    → Priority distribution → Recent incidents → Map context
```

Reuse the Explorer filters. Do not introduce a second filtering architecture for
the detail view.

---

# 13. Phase 5 — Category Explorer

## Goal

> What kinds of civic problems are occurring, where, and how are they changing?

Capabilities: category summary, table, trend, ward distribution, active
incidents, high-priority incidents, resolution metrics.

All aggregation uses `complaints.category` — the canonical code — never
`source_category`. `source_category` exists for audit and debugging: it is what
the caller sent, preserved so a normalisation mistake is traceable. Aggregating
on it would reintroduce the four-spellings-of-one-problem bug the registry was
built to end.

**Resolution metrics are now possible, with one honest limit.**
`incidents.resolved_at` (V18) records when an incident was resolved and is
cleared if it is reopened. Two things follow:

- Metrics begin **2026-10-06**. No incident resolved before that has a
  resolution time, and none will be given one — every candidate source for a
  backfill would have been a guess presented as a measurement.
- **Never compute resolution time as `updatedAt − createdAt`.**
  `PriorityRefreshJob` touches every open incident every five minutes, so that
  subtraction measures the sweep, not the work. This is the same class of error
  as the `created_at`/`reported_at` conflation, and it fails the same way:
  silently, with plausible numbers.

---

# 14. Category Detail

```text
Category → Complaint trend → Incident count → Ward distribution
    → Priority distribution → Recent incidents
```

Do not create category semantics in the frontend. The display name and the
canonical code come from `/categories`. The alias set does **not** — it is
deliberately kept off the wire (see
[category-dataset-audit.md](category-dataset-audit.md)); the frontend never
needs it, because it only ever submits codes.

---

# 15. Phase 6 — Incident Explorer

The most important step after the basic Explorer: shift from *complaint
dashboard* to *incident intelligence system*.

An incident exposes:

```text
Incident ID        Category          Ward            Location
Complaint count    First reported    Last reported   Priority score
Priority band      Priority reasons  Status          Resolved at
Related complaints
```

Two corrections to the draft's field list:

- **`Severity` is not in it** — it does not exist. `priorityScore` +
  `priorityBand` + `priorityReasons` are the ranking, the bucket and the
  justification.
- **`First reported` / `Last reported` are derived**, not columns:
  `min`/`max(complaints.reported_at)` over members. Do not read
  `incidents.created_at` for "first reported" — that is when the grouping was
  created, which for imported members is a different thing.

---

# 16. Incident Detail

Make the relationship explicit:

```text
Incident
   ├── Complaint A
   ├── Complaint B
   └── Complaint C
```

Show: the complaint timeline (ordered by `reported_at` — the endpoint already
does this), location, ward, category, priority with its reasons, status, latest
activity.

**Explainability already exists at two levels.** `incidents.priority_reasons`
says why this incident ranks where it does. `incident_match_log` records, per
decision, which matcher ran, the similarity, and the outcome — that is the "why
are these grouped" answer. Surface the first to operators freely. Surface the
second with care: a cosine similarity means nothing to an officer, while "matched
to a report 40 m away about the same problem" means everything.

Do not expose model internals that no operator can act on.

---

# 17. Phase 7 — Matching Evaluation

The semantic matcher is operational. Evaluate it before changing it.

Build an evaluation set:

**Positive pairs** — same pothole, same garbage accumulation, same streetlight
failure, same water leak, same drainage issue.

**Negative pairs** — same category far apart, different categories nearby, same
category different incidents, similar language different problem.

Measure precision, recall, false positives, false negatives. Then set a
threshold policy.

Two things to know before starting:

- The current threshold is **0.75**, and [its measured margin is
  thin](matching-contract.md). That is the headline number the evaluation exists
  to confirm or move.
- `incident_match_log` is the raw material — every decision already has a row.
  `PriorityRefreshJob` deliberately never writes to it, specifically so
  recomputing a score cannot fabricate match decisions and corrupt this dataset.
  Keep that property.

Do not tune on one or two smoke tests.

---

# 18. Matching Architecture

```text
New Complaint → Hard constraints → Candidate retrieval
    → Semantic similarity → Spatial/temporal compatibility
    → Decision threshold → Attach OR create
```

The implementation can stay as built unless evaluation shows a weakness. The
architectural principle:

> Semantic similarity should not be the only signal.

Candidate signals: category compatibility, spatial distance, time difference,
semantic similarity, existing incident state. Add them where evaluation proves
they improve correctness — not for completeness.

One known property worth evaluating rather than assuming: matching is
**single-linkage**, so A–B and B–C can chain A to C even if A and C would never
have matched directly. That is recorded as a deliberate decision with its own
revisit trigger in [matching-contract.md](matching-contract.md), and it is
exactly the kind of thing a labelled set can settle.

---

# 19. Phase 8 — Incident Priority

**Explainability is already shipped**, not future work: `priority_reasons` is a
persisted array of operator-readable statements, ordered by how much each term
contributed, written at computation time. The draft's example is close to what
the column already holds:

```text
HIGH
- 12 complaints reported
- 3 new complaints in the last 24 hours
- reports spread over 400 m
```

What is genuinely open is **calibration**. The weights (40/30/20/10) and bands
(3.0 / 5.5 / 7.5) are hand-set and frozen until a real morning's queue has been
ranked by an officer — 200 labelled incidents, or the first complaint that the
ranking is wrong, whichever comes first. The measured defects, the first change
to try, and the reasons not to touch it sooner are all in
[priority-evaluation.md](priority-evaluation.md).

**`severity` is not an input and is not being added.** A category weight would
be a fifth term in a formula whose four weights are explicitly frozen; adding
one now would change what those weights mean on no evidence. If calibration
shows a category is systematically under-ranked, the fix is
`categories.severity_weight` read by `PriorityService` — never a field on
`incidents`.

Priority must never become a mysterious ML output.

---

# 20. Phase 9 — Hotspots

Only after incident data is reliable. First version from existing incidents.

```text
Geographic concentration + Temporal concentration + Category concentration
```

A hotspot needs a written definition before it has an implementation. Do not
label arbitrary dense map areas as hotspots — the spread term in the priority
formula carries the least weight for exactly this reason: two reports 900 m apart
may simply be two different potholes.

---

# 21. Phase 10 — Operational Intelligence

Instead of:

```text
There are 31 incidents.
```

surface:

```text
12 high-priority incidents
3 rapidly worsening
2 concentrated in Ward 48
1 category spike in the last 24 hours
```

Every one of those must drill through to the underlying incidents. An insight an
operator cannot open is a claim they cannot check.

---

# 22. Future Decision-Support Layer

After Explorer and incident intelligence are stable:

```text
Incident → Severity of impact → Priority → Affected population/area
    → Required department → Recommended action
```

Possible outputs: investigate immediately, dispatch field team, consolidate
related complaints, monitor, escalate, close after verification.

A decision-support layer, not an autonomous authority.

---

# 23. Ingestion Architecture

```text
External Source → Raw ingestion record → Validation → Hash / idempotency
    → Category normalization → Ward resolution → Canonical complaint
    → Matching → Incident → Priority
```

This is built and tested; `/internal/ingest` is the only door. Each stage is
observable through `ingestion_batches`, `ingestion_records` and the
`ingestion_rejections` view.

Two properties to preserve when the first feed arrives:

- **Raw first.** The record is stored before it is judged, so a rejection is
  reviewable rather than merely counted.
- **One transaction per record**, in its own bean so `REQUIRES_NEW` is not
  defeated by proxy self-invocation. One bad record costs that record.

And one thing that is now closed and must stay closed: **an upstream `photoUrl`
never reaches `complaints.photo_url`.** It is preserved in
`ingestion_records.raw` as evidence. See
[photo-upload-contract.md](photo-upload-contract.md) §8.

---

# 24. Ingestion Idempotency

```text
Same source record → Same logical hash → No duplicate complaint
```

Of the draft's six test cases, the current implementation already settles two by
construction. The hash is taken over `ExternalComplaint` — a flat, all-`String`
DTO — serialised through a `TreeMap`:

| Case | Status |
|---|---|
| Identical payload twice | Same hash → `UNCHANGED`. Covered by `IngestionServiceTest`. |
| Reordered JSON fields | Cannot change the hash: keys are sorted, and the DTO fixes the shape. |
| Changed irrelevant metadata | Cannot change the hash: fields not on the DTO never reach it. |
| Null fields / missing optionals | Covered by `IngestionValidatorTest`. |
| **Changed source timestamp** | **Open decision.** `reportedAt` is inside the hash, so a feed that re-emits a record with a corrected timestamp registers as CHANGED, not UNCHANGED. Decide whether that is a content change (current behaviour) or a correction to absorb. |

Only fields that define logical identity should influence the hash. The hashing
`ObjectMapper` is private and static for that reason — a shared, reconfigurable
mapper would silently change every hash the day someone added a module, and the
next run would re-import the world.

---

# 25. Observability

Structured logs plus DB metrics are sufficient. Do not build a platform.

**Ingestion** — received, rejected, normalized, duplicated, created. All
derivable from `ingestion_records.status` grouped by batch.

**Matching** — matched, new incident, degraded, failed. In
`incident_match_log.outcome` and `complaints.matching_status`.

**Priority** — computed, failed, stale. `PriorityRefreshJob` already logs
refreshed/failed counts per sweep; stale is a query on `priority_computed_at`.

**Data** — invalid ward, unknown category, dataset WARN, dataset FAIL. The first
two are rejection reasons; the last two are
`select * from dataset_validations where status <> 'PASS'`, which the partial
index exists to serve.

---

# 26. Database Discipline

Every migration: additive where practical, one clear purpose, tested against
realistic data, no destructive change unless planned, rollback considered,
documented.

Never modify production schema outside the migration process.

**Know this before running anything:** `./mvnw test` runs Flyway against
`DB_URL`, which is the live Neon database. Tests migrate production. That is how
V11–V18 shipped, and it means schema can reach production ahead of the code that
needs it — which has happened once, and left the ward resolver answering with
retired placeholder wards until the deploy caught up.

---

# 27. Production Safety

```text
Backup/branch → Migration → Tests → Deploy → Smoke test → Verify data
```

Never assume:

> migration succeeded = application is healthy.

Two specifics earned the hard way:

- **Editing a Railway variable does not restart a running container.** Setting
  `WARD_RESOLVER=postgis` changed nothing until the service was redeployed, and
  the failure was silent — every complaint still got a ward, just the
  approximate one.
- **Probe where implementations disagree.** An endpoint that answers is not an
  endpoint that is right. `12.973256, 77.510895` resolves to ward 48 under
  PostGIS and ward 46 under the centroid resolver; that is the test that proves
  which one is running. The runbook is
  [release-readiness.md](release-readiness.md) §8.

---

# 28. API Performance

Do not optimize prematurely. Implement correctly, measure, find the slow query,
index it, re-measure.

Query dimensions: ward, category, status, priority, `reported_at`, location.

Indexes that already exist and should be reused rather than duplicated:
`complaints(reported_at)`, `complaints(ward_id)`,
`incidents(status, priority_computed_at)`,
`incidents(resolved_at) where resolved_at is not null`, a GiST index on
`wards.boundary`, and the pgvector index on complaint embeddings.

Avoid indexing every column. An index nothing queries is a write cost with no
reader.

---

# 29. Pagination and Data Limits

Every list endpoint: `limit`, `offset`/cursor, sorting — or another explicit
bounded strategy. Never let the Explorer request unbounded data.

Maps should not render thousands of individual markers when aggregation serves.
Prefer cluster → aggregate → summarize → drill down.

---

# 30. Security Boundaries

Authentication, authorization, validation, sanitization and filtering stay on
the backend. Never trust a frontend filter for access control.

Two rules already load-bearing in this codebase:

- **404, not 403**, for a resource that exists but is not yours. The distinction
  is an enumeration oracle over ids.
- **Prefer the reference number** (`CP-2026-W48-00001`) over the surrogate id in
  anything citizen-facing.

---

# 31. Documentation Structure

The draft's file list would fork seven documents that already exist. Use these:

| Topic | Authoritative file | Action |
|---|---|---|
| Architecture / service split | [service-boundaries.md](service-boundaries.md) | extend |
| Ward dataset | [ward-data.md](ward-data.md) | extend (§5.1) |
| Category dataset | `category-dataset-audit.md` | **create** (§5.2) |
| API contract | [api-contract.md](api-contract.md) + [endpoints.md](endpoints.md) | extend (§6) |
| Data quality rules | `data-quality-rules.md` | **create** — policy drafted in §5.3 |
| Ingestion | [service-boundaries.md](service-boundaries.md) | thinnest coverage; extend or split out |
| Matching | [matching-contract.md](matching-contract.md) | already complete |
| Priority | [priority-evaluation.md](priority-evaluation.md) | already complete |
| Deployment verification | [release-readiness.md](release-readiness.md) §8 | already complete |
| Audit history | [audit-register.md](audit-register.md) | keep; carries the ISSUE-ID translation table |

Only two files are genuinely new. Creating the other seven under new names is how
documentation diverges — two files, two answers, and no way to tell which is
current.

---

# 32. Recommended Repository Milestones

**Milestone 1 — Data Foundation.** Category audit, ward audit extension, data
quality rules, API contract extension.
*Exit: dataset and API assumptions are explicit.*

**Milestone 2 — Explorer MVP.** Explorer shell (refactored out of
`IncidentDashboard.tsx`), shared filters, Ward Explorer, Category Explorer,
loading/error/empty states.
*Exit: an operator can explore current civic activity reliably.*

**Milestone 3 — Incident Intelligence.** Incident Explorer, incident detail,
complaint drill-down, priority visibility, timeline.
*Exit: operators understand incidents, not just complaints.*

**Milestone 4 — Matching Evaluation.** Evaluation dataset, precision/recall,
threshold decision, documented behaviour.
*Exit: matching quality is measured rather than assumed.*

**Milestone 5 — Operational Intelligence.** Trends, hotspots, worsening
incidents, category spikes, ward pressure.
*Exit: Explorer identifies what deserves attention.*

**Milestone 6 — Decision Support.** Recommended action, resource implications,
escalation, explainable recommendations.
*Exit: actionable decision support rather than analytics alone.*

---

# 33. What NOT to Do

- rewrite the backend because a cleaner architecture exists
- introduce microservices, Kafka or Kubernetes without a demonstrated requirement
- replace PostgreSQL/PostGIS unnecessarily
- replace the matcher before evaluating it
- add LLMs where deterministic mappings are safer
- duplicate category logic in frontend code — including a category→severity map
- duplicate ward logic outside PostGIS
- build dashboards against raw DB entities
- create dozens of narrowly overlapping APIs
- add ML before establishing evaluation data
- optimize queries before measuring them
- add features without defining their domain meaning
- **add a field the roadmap assumes exists without checking that it does**

---

# 34. Definition of Done for Any New Feature

```text
Requirement defined → Domain behavior defined → Backend contract defined
    → Database/query implemented → Tests added → Frontend implemented
    → Error/empty states handled → Production behavior verified
    → Documentation updated
```

A feature is not done because the UI works.

---

# 35. Recommended Immediate Sprint

**Step 1 — Documentation.** ✅ Done 2026-10-13:
[category-dataset-audit.md](category-dataset-audit.md) and
[data-quality-rules.md](data-quality-rules.md) created; `ward-data.md` extended
(counts, LGD coverage, overlap check); `api-contract.md` extended (dashboard
shapes, Explorer query conventions, known gaps). Decisions it surfaced are
recorded in data-quality-rules.md §6: `CLOSED` = no fix (excluded from
resolution metrics), time to resolve starts at the first report, provisional
department per category (V19), ward overlap persisted (V20), imports accept
retired categories.

**Step 2 — Endpoint classification.** ✅ Done 2026-10-13 — "Endpoint
classification" in [api-contract.md](api-contract.md): 4 new endpoints, 5
extended, 2 deferred, 0 new controllers. Implementation order: the E5/E6
correctness fixes first (no frontend consumer, so nothing breaks), then E1 + the
console together (breaking), then N1–N4 with the Explorer shell. Progress
2026-10-13: E5's two fixes ✅, E1 + E2 report span + console ✅ (V21).
2026-10-14: N1–N4, E2 owning department, E4, E5, E6 ✅ — the whole
classification is implemented; typed clients in `frontend/src/services/
explorer.service.ts`. **Next: step 3, the Explorer shell.**

**Step 3 — Explorer shell**, refactored out of `IncidentDashboard.tsx`.

**Step 4 — Ward Explorer.**

**Step 5 — Category Explorer.**

**Step 6 — Run it against real data** (167 complaints, 31 incidents, 243 wards).

**Step 7 — Fix what actual usage exposes**, correctness before performance.

**Step 8 — Incident Explorer.**

---

# 36. Current Priority Order

```text
P0 — Production correctness
P1 — Data correctness
P2 — API/domain correctness
P3 — Explorer usability
P4 — Incident intelligence
P5 — Matching quality
P6 — Operational intelligence
P7 — Decision support
P8 — Infrastructure optimization
```

Never work on P6/P7 while a P0/P1 problem is open.

---

# 37. Ownership Model

Implementation ownership transfers with this document.

**Before coding:** understand the existing implementation → identify the affected
domain → check the existing endpoint/service → check the existing migration →
plan the smallest coherent change.

**During:** implement → test → review side effects → update documentation.

**Before merge/deploy:** tests → build → migration verification → smoke test →
review.

---

# 38. Final Architectural Rule

The system should remain understandable by one engineer. A change that makes the
architecture significantly harder to explain needs a concrete reason.

Favour simple, explicit, testable, observable, correct over distributed,
abstract, clever, AI-heavy, prematurely scalable.

---

# 39. Final Direction

The stabilization phase is complete. The foundation gives us:

```text
Reliable ward resolution  +  Canonical categories  +  Separated timestamps
+  Safe reconciliation    +  Working matching      +  Working priority
+  Persisted dataset validation  +  Recorded resolution time
```

The next milestone is not another migration. It is:

> **A reliable Explorer that lets a municipal operator move from city → ward →
> category → incident → underlying complaints.**

Then evaluate matching quality, then build the operational-intelligence layer.

One habit to carry forward, because it is what the stabilization phase actually
taught: **every number this system shows an operator should be traceable to a
column that was written on purpose.** Each of the three worst bugs found — the
timestamp conflation, the 5.5-hour clock, the reconcile starvation — produced no
error and no failure. They produced plausible numbers that were wrong. Nothing in
the Explorer will announce that it is lying either.

---

# 40. Final One-Line Roadmap

> **Data trust → API contracts → Explorer → Incident intelligence → Matching
> evaluation → Operational intelligence → Decision support.**
