# Data Quality Rules

The rules that make CivicPulse's numbers trustworthy, in one place: how
reference data gets in, what every write path enforces, and how every read must
interpret what it finds.

Most of these rules already exist — in migrations, service javadoc and the
roadmap. This document is where they are stated as rules, so the Explorer is
built against them rather than rediscovering them. Where the code does not yet
follow a rule, §5 says so.

Related: [ward-data.md](ward-data.md), [category-dataset-audit.md](category-dataset-audit.md),
[api-contract.md](api-contract.md), [matching-contract.md](matching-contract.md),
[priority-evaluation.md](priority-evaluation.md).

---

## 1. Reference dataset imports

Wards and categories are reference data: every complaint and incident is keyed
on them, so a bad import corrupts everything downstream, silently.

### The gate

```text
New dataset → Parse → Validate → Persist results → Gate on worst status

FAIL → block the import entirely. No partial loads.
WARN → import permitted ONLY with a documented reason in
       dataset_validations.detail. "Tolerated" is written down, not remembered.
PASS → normal import.
```

Results go to `dataset_validations` — one row per check per dataset version —
never only to a log. A `RAISE NOTICE` survives exactly as long as whoever was
watching the deploy.

**Current state** (2026-10-13): one dataset, `bbmp-wards / 2022-delimitation`,
five checks — four PASS, one WARN (`lgd-code-coverage`, 45 wards with no
national LGD code, tolerated with its reason recorded). V20 adds a sixth,
`boundary-overlap`, which records PASS. Details in
[ward-data.md](ward-data.md#audit--2026-10-13).

**There is no runtime writer.** `dataset_validations` is written only by
migrations (V17's backfill) and `generate-ward-migration.mjs`. The first
importer that runs outside a migration must implement this gate; it is not
optional and is not "later".

### Rules learned the hard way

- **Re-derive what you can, transcribe what you cannot, and say which.** Four of
  the five ward rows are re-measured from current rows by V17's own SELECTs.
  `repair-area-drift` is transcribed from the V13 run, because the pre-repair
  geometry no longer exists — and its `detail` says so.
- **Never regenerate over an applied migration.** Flyway checksums applied
  migrations, so regenerating V13 would not re-import anything; it would break
  startup everywhere V13 already ran. The generator refuses. A re-import is a
  **new** migration, numbered above the current head.
- **Measure repairs.** `ST_MakeValid` can return a geometry of very different
  area. V13 refuses to apply if any repair moves area by more than 0.5%.
- **Never invent a mapping.** A missing LGD code stays null. A missing zone stays
  null. A WARN is a fact about the source.
- **Retire, don't delete.** A reference row that anything has pointed at gets
  `active = false`, not `DELETE`. Issued reference numbers encode it.

---

## 2. Write-path rules — enforced today

Every rule here is already implemented. Listed so nobody adds a write path that
skips one.

| Rule | Enforced by | Applies to |
|---|---|---|
| **Category is normalised on write to a canonical code.** Unknown → rejected, never stored, never swept into `other`. | `CategoryService.require` / `.resolve` | `POST /complaints`, `/internal/ingest` |
| **The raw category is preserved** in `source_category` when it differed. | `ComplaintService`, `IngestionRecordProcessor` | both |
| **Ward comes from point-in-polygon containment.** No nearest-ward fallback; outside every ward → 404 / rejection. | `PostGisWardResolver` | both |
| **Only active wards are assignable.** `LEGACY-*` placeholders are never resolved into. | `findContaining` (`w.active`), ingest ward lookup | both |
| **`reported_at` is when the problem was reported; `created_at` is when the row was created.** `created_at` is never rewritten. `reported_at` is `NOT NULL`, not in the future, not before 1970. | V16, `IngestionValidator` | both |
| **No caller-supplied photo URL is stored.** `photo_url` holds only a key the server mints. | `CreateComplaintRequest` (field removed), `IngestionValidator` | both |
| **External data enters only through the ingestion gate**: raw payload persisted first, every rejection reason collected, idempotent on `(source, source_record_id)`, one transaction per record. | `/internal/ingest/{source}` | ingest |
| **`resolved_at` is written only by the status transition** — stamped on entering `RESOLVED`, cleared on leaving it for any status (including `CLOSED`, which means no fix), unchanged on re-sending `RESOLVED`. | `IncidentService.applyStatus`, `IncidentResolutionTest` | `PATCH /incidents/{id}` |
| **A retired category is refused on new reports but accepted on import** — an import is history. | `CategoryService.require`; `IngestionValidator` | both |
| **Priority is recomputed only by membership change or the sweep**, never by `PATCH`. | `IncidentService`, `PriorityRefreshJob` | incidents |
| **Timestamps are UTC.** | JVM-wide `TimeZone.setDefault` | everything |

The last row is the weakest. Columns are `timestamp without time zone`, and
UTC-ness is a process-level guarantee: any other writer to the database is
outside it. Moving to `timestamptz` is the one schema change worth considering,
as a deliberate piece of work.

---

## 3. Read-path rules — for every aggregate, filter and chart

These are the definitions. An endpoint that answers one of these questions uses
exactly this meaning; it does not pick its own.

| Question | Definition | Never |
|---|---|---|
| Which category? | `complaints.category` / `incidents.category` — the canonical code | `source_category` (audit only) |
| When was it reported? Date filters (`from`/`to`), trends, "last 7 days" | `complaints.reported_at` | `created_at` |
| In what order did it reach us? | `complaints.id` / `created_at` | `reported_at` (a feed controls it) |
| When was an incident first / last reported? | `min` / `max(complaints.reported_at)` over its members | `incidents.created_at` (when the *grouping* was made) |
| Active incident | `status IN (OPEN, IN_PROGRESS)` | — |
| Fixed vs not fixed | `RESOLVED` = the problem was fixed. `CLOSED` = terminated **without** a fix (duplicate, invalid, out of scope). Same meaning on complaints and incidents. | counting `CLOSED` as a resolution |
| High-priority incident | `priorityBand IN (high, critical)`, i.e. `priority_score >= 5.5`, computed server-side | a client-side threshold; `severity` (does not exist) |
| How fresh is a ranking? | `incidents.priority_computed_at` | `updated_at` (any write moves it) |
| When was an incident resolved? | `incidents.resolved_at` — set only while `status = RESOLVED` | `updated_at − created_at` (measures the 5-minute sweep) |
| Time to resolve | `resolved_at − min(member complaints.reported_at)`: from the first report to the fix, what the resident experienced. `RESOLVED` incidents only. | `incidents.created_at` as the start; including `CLOSED` incidents |
| Which department owns it? | `COALESCE(incidents.department_id, categories.default_department_id)`; NULL = triage by hand | a frontend category→department map |
| Which wards exist? | `wards.active = true` (243) | the 4 `LEGACY-*` rows — never on a map, picker or ward table |
| Where was it? | the complaint's own `ward_id` | re-resolving coordinates at read time |

### Resolution metrics start on 2026-10-06

`resolved_at` was added by V18. No incident resolved before then has a
resolution time, and none will be given one: every backfill source would be a
guess presented as a measurement. A resolution chart covering earlier dates must
say its data starts there rather than show zero. As of 2026-10-13 **no incident
has been resolved**, so every resolution metric is currently empty — that is a
correct answer, and the Explorer must render it as "no resolved incidents yet",
not as an error or a zero-hour average.

### Small numbers

Counts small enough to identify a person stay behind the officer role. The
public `GET /wards/summary` is counts-only by design and must never carry a
category breakdown, coordinates, titles or scores — see
[api-contract.md](api-contract.md#get-wardssummary).

---

## 4. Monitoring queries

Read-only. Every one has an expected answer; a different answer is a data
quality incident.

```sql
-- Reference data: anything not PASS. Expected: exactly 1 row (the LGD WARN).
-- After V20, 'boundary-overlap' is PASS and does not appear here.
SELECT dataset_name, check_name, status, observed, detail
FROM dataset_validations WHERE status <> 'PASS';

-- Category quarantine. Expected: empty.
SELECT category, count(*) FROM complaints
WHERE category NOT IN (SELECT code FROM categories) GROUP BY 1;

-- Complaints filed in a retired ward. Expected: 0.
SELECT count(*) FROM complaints c JOIN wards w ON w.id = c.ward_id WHERE NOT w.active;

-- resolved_at agrees with status. Expected: 0.
SELECT count(*) FROM incidents
WHERE (status = 'RESOLVED') <> (resolved_at IS NOT NULL);

-- Priority sweep alive. Expected: 0 within two sweep intervals.
SELECT count(*) FROM incidents
WHERE status IN ('OPEN','IN_PROGRESS') AND priority_computed_at IS NULL;

-- Report time vs arrival time. 0 until a feed backfills, then the number of
-- imported complaints, and every dashboard window must still use reported_at.
SELECT count(*) FROM complaints WHERE reported_at <> created_at;

-- Matching pipeline backlog. A growing DEGRADED/PENDING count means Node is down.
SELECT matching_status, count(*) FROM complaints GROUP BY 1;

-- What external feeds are getting wrong.
SELECT * FROM ingestion_rejections ORDER BY occurrences DESC;
```

---

## 5. Where the code does not yet follow these rules

Found while writing this document. None has produced a wrong number in
production yet — each is latent because of the current data — and each must be
fixed before the Explorer reads through it.

| # | Rule broken | Where | Why it is invisible today | Effect once it isn't |
|---|---|---|---|---|
| 1 | Date windows use `reported_at` | ~~`DashboardService` windows counted `createdAt`~~ | | **Fixed 2026-10-13**, `DashboardServiceTest` |
| 2 | Ranked queues are of active incidents | ~~`DashboardService.topIncidents` ranked any status~~ | | **Fixed 2026-10-13**, `DashboardServiceTest` |
| 3 | ~~Every list endpoint is bounded (roadmap §29)~~ | ~~both lists were unbounded~~ | | **Fixed**: `GET /incidents` 2026-10-13 (E1), `GET /complaints` 2026-10-14 (E4) |
| 4 | Ward lookup is unique | 26 sliver overlaps between ward boundaries | no complaint sits in one | a point in a sliver resolves to the lower ward id — deterministic, documented in [ward-data.md](ward-data.md#overlap-check), not fixed |
| 5 | ~~Results are persisted, not just observed~~ | ~~the ward overlap check was not in `dataset_validations`~~ | | **Fixed by V20** (`boundary-overlap`, PASS) once applied |

Items 1–3 are fixed. Item 4 is documented and deliberately not repaired.

---

## 6. Decisions

All decided 2026-10-13.

1. **`CLOSED` means terminated without a fix; `RESOLVED` means fixed.** So
   `RESOLVED → CLOSED` correctly clears `resolved_at` — it says the fix never
   happened — and `CLOSED` incidents are excluded from resolution metrics. The
   existing code already behaved this way; the definition is now in
   `IncidentStatus` / `ComplaintStatus` and pinned by two tests in
   `IncidentResolutionTest`.
2. **Time to resolve starts at the first member complaint's `reported_at`.**
3. **The ward overlap check is persisted** as a sixth `bbmp-wards` row
   (`V20__ward_overlap_validation.sql`), re-derived from the boundaries, PASS
   while no single pair overlaps by more than 100 m² (today: 8.70 m²).
4. **Each category has a provisional default department**
   (`V19__category_default_department.sql`); see
   [category-dataset-audit.md](category-dataset-audit.md#department-ownership).
5. **Imports accept retired categories.** See §2.
