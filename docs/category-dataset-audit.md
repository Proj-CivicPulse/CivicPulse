# Category Dataset Audit

What the category registry contains, how it is used, and what it does not yet
decide. The companion to [ward-data.md](ward-data.md) for the other reference
dataset every aggregate depends on.

**Measured against the live Neon database on 2026-10-13** (Flyway head V18,
167 complaints, 31 incidents). Every count below is a query result, not a
recollection; the queries are at the end so the next audit can re-run them.

---

## Why this dataset matters

`complaints.category` and `incidents.category` are **exact-equality keys**. The
semantic matcher's candidate pre-filter (ward + category + status), the naive
fallback, the dashboard breakdown and every future Explorer aggregate compare
them with `=`. Two spellings of one problem therefore mean two incidents that
never merge and two rows on every chart, with no error anywhere.

The registry exists to make that impossible: **normalise once on the way in,
then compare canonical codes forever after.** There is no fuzzy matching at
query time, anywhere.

---

## The canonical codes

8 categories, all active, all top-level (`parent_id` is null on every row).

| Code | Officer name (`name`) | Resident label (`display_name`) | Order | Aliases | Complaints | Incidents | Active incidents | Added by |
|---|---|---|---|---|---|---|---|---|
| `pothole` | Pothole / road damage | Pothole or damaged road | 10 | 9 | 59 | 4 | 4 | V11 |
| `streetlight` | Street lighting | Street light out | 20 | 8 | 39 | 4 | 4 | V11 |
| `garbage` | Solid waste | Garbage not collected | 30 | 10 | 29 | 6 | 6 | V11 |
| `water` | Water supply | Water supply or leak | 40 | 9 | 16 | 6 | 6 | V11 |
| `drainage` | Drainage | Blocked drain or waterlogging | 50 | 9 | 12 | 4 | 4 | V11 |
| `footpath` | Footpath | Damaged or blocked footpath | 60 | 8 | 4 | 1 | 1 | V14 |
| `noise` | Noise nuisance | Excessive noise | 70 | 6 | 4 | 2 | 2 | V14 |
| `other` | Other | Something else | 99 | 5 | 4 | 4 | 4 | V11 |
| | | | | **64** | **167** | **31** | **31** | |

- **`code` is permanent.** It is the literal value stored on every complaint and
  incident. `name` and `display_name` are labels and may be reworded freely.
- **Nothing sits outside the registry.** Zero complaints and zero incidents
  carry a value that is not a canonical code — V14's post-condition, re-checked
  today.
- **The distribution is seed data, not city data.** All 167 complaints arrived
  directly (none through `/internal/ingest`), and the skew toward `pothole` and
  `streetlight` reflects how the development dataset was written. Do not read
  it as a finding about Bengaluru.

### `footpath` and `noise` — found in data, not invented

V11 seeded six codes and deliberately **did not** sweep unrecognised values
into `other`; it left them in place and documented a quarantine query to find
them. Run against real data, that query returned exactly two values, each on
4 complaints: `noise` (2 incidents) and `footpath` (1 incident). Neither was a
typo. V14 promoted both to canonical codes.

The reasoning is recorded in V14 and is worth keeping in mind for the next one:

- `footpath` is **not** `pothole`. A pedestrian hazard and a carriageway defect
  go to different works teams and rank differently.
- `noise` has no relative at all. Folding it into `other` would bury a distinct,
  actionable complaint type in the bucket reserved for things with no home.

That is the process for any future unknown value: **surface, review, decide** —
never a silent catch-all.

---

## Aliases

64 rows in `category_aliases`, **all with `source = 'internal'`**. No external
feed vocabulary exists yet. Each canonical code is also seeded as its own alias,
so resolution is always one lookup.

Aliases are stored **already normalised** — lowercase, each run of
non-alphanumeric characters collapsed to one space, trimmed. `CategoryNormalizer`
applies the identical transform to incoming values (plus accent folding, which
is a strict superset), so resolution is a plain equality hit on
`category_aliases.alias`. `CategoryNormalizerTest` asserts the two agree.

| Code | Aliases (`internal`) |
|---|---|
| `pothole` | pothole, potholes, road damage, damaged road, broken road, bad road, road repair, road, roads |
| `streetlight` | streetlight, streetlights, street light, street lights, street lighting, street lamp, light not working, lighting |
| `garbage` | garbage, garbage not collected, uncollected garbage, solid waste, waste, trash, rubbish, litter, refuse, sanitation |
| `water` | water, water supply, water leak, leak, leakage, pipeline leak, burst pipe, no water, water shortage |
| `drainage` | drainage, drain, blocked drain, open drain, sewage, sewer, waterlogging, water logging, storm water |
| `footpath` | footpath, footpaths, sidewalk, pavement, broken footpath, damaged footpath, blocked footpath, footpath encroachment |
| `noise` | noise, noise pollution, noise nuisance, loud noise, loudspeaker, construction noise |
| `other` | other, others, miscellaneous, general, something else |

**What is deliberately not an alias.** No alias maps a value to a category it
merely resembles. `sewage` is `drainage`, not `water` — a supply failure and a
sewage overflow belong to different departments, and merging them would be a
worse bug than the fragmentation the registry fixes. Likewise there is no
stemming or edit distance: `water` must never reach `waterlogging`.

### Source scope

`(source, alias)` is unique, so the same spelling can mean different things in
different vocabularies. Resolution order:

| Path | Lookup |
|---|---|
| `POST /complaints` (our UI and public API) | `internal` only |
| `POST /internal/ingest/{source}` | the feed's own `source` first, then `internal` |

A feed whose vocabulary uses a word differently gets its own alias rows under
its own source key, and cannot hijack the meaning of ours.

### Values that were actually normalised

`source_category` keeps the raw string whenever it differed from the stored
code. In production exactly one spelling has ever been rewritten:

| Stored | Raw (`source_category`) | Complaints | Incidents |
|---|---|---|---|
| `garbage` | `sanitation` | 4 | 2 |

Every other row's raw value already was the canonical code, so its
`source_category` is null.

**Never aggregate on `source_category`.** It exists so a normalisation mistake
is traceable and reversible. Grouping by it reintroduces exactly the
four-spellings-of-one-problem bug the registry ends.

---

## Rejection at the boundary

An unknown category is **refused, not stored** — an unrecognised value files a
report no aggregate will ever find.

| Path | Unknown value | Retired (`active = false`) value | Where the rejection surfaces |
|---|---|---|---|
| `POST /complaints` | `400 VALIDATION_ERROR`, message lists the accepted codes | `400 VALIDATION_ERROR`, "no longer accepted" | **Application log only** — `CategoryService` logs `Rejected unknown category` at INFO. Not persisted. |
| `POST /internal/ingest/{source}` | record `REJECTED`, reason `{field: "category", code: "UNKNOWN_VALUE"}` | **Accepted**, deliberately — see decision 2 | `ingestion_records.rejection_reasons`, summarised by the `ingestion_rejections` view with a sample payload |
| Read filters (`?category=` on `/complaints`, `/incidents`) | passed through untouched — matches only rows literally carrying it | resolves normally | n/a — a filter must not 400 |

The frontend submits codes it reads from `GET /categories`, so in practice only
a direct API client reaches the public-path rejection.

---

## Department ownership

**Provisional mapping, decided 2026-10-13** and encoded by
`V19__category_default_department.sql` as `categories.default_department_id`.
The team will revisit it once BBMP's real department structure is known; the
four V2 departments are seed data, not that structure.

| Code | Default department | Why |
|---|---|---|
| `pothole` | Public Works | carriageway works |
| `footpath` | Public Works | same department as `pothole`, different team (V14); the category stays separate, which is what matching needs |
| `streetlight` | Street Lighting | |
| `garbage` | Sanitation | |
| `water` | Water Supply | |
| `drainage` | **Storm Water Drains** — added by V19 | V11's rule: sewage and waterlogging are not Water Supply's problem, and none of the four seeded departments owned them |
| `noise` | *none* | enforced by the police and the pollution control board, not a municipal works department |
| `other` | *none* | no owner by definition — triage by hand |

**It is a default, not an assignment.** `department_id` on complaints and
incidents is still set only by an officer (`PATCH`), and V19 touches no
complaint or incident. The department that owns an incident is:

```sql
COALESCE(incidents.department_id, categories.default_department_id)  -- NULL = triage by hand
```

Auto-filling `department_id` on new incidents from the default is a separate
decision, not taken. Usage today: no complaint or incident has a department set
by an officer.

---

## Hierarchy and retirement

- **`parent_id`** exists so a future rollup (e.g. `pothole` under a `roads`
  parent) is not another migration of every consumer. Unused: every category is
  top-level.
- **`active`** is the retirement mechanism. A retired category stays readable —
  old complaints still reference it — but drops out of `GET /categories` and is
  refused on `POST /complaints`. **No category has been retired.**
- **No deprecated categories exist.** There is nothing to migrate away from.

---

## Decisions and open questions

1. **Department per category — decided, provisional** (2026-10-13). See
   above. Revisit when real departments are known.
2. **Ingestion accepts a retired category — decided** (2026-10-13). An import
   is history: a report filed upstream while the category was live is still a
   true record, and retiring a category later must not make it unimportable.
   The public path keeps refusing it. Pinned by
   `IngestionValidatorTest.acceptsRetiredCategory`.
3. **Public-path rejections are invisible — open, low priority.** They are
   logged, not persisted, so "what are callers trying to submit that we
   refuse?" cannot be answered from the database. Matters only once something
   other than our own form calls `POST /complaints`.

---

## Re-running this audit

All read-only.

```sql
-- Canonical codes with usage
SELECT c.code, c.active, c.sort_order,
       (SELECT count(*) FROM complaints x WHERE x.category = c.code) AS complaints,
       (SELECT count(*) FROM incidents  i WHERE i.category = c.code) AS incidents,
       (SELECT count(*) FROM incidents  i WHERE i.category = c.code
          AND i.status IN ('OPEN','IN_PROGRESS'))                    AS active_incidents
FROM categories c ORDER BY c.sort_order, c.code;

-- Quarantine report: MUST be empty. A row here is a new category decision.
SELECT category, count(*) FROM complaints
WHERE category NOT IN (SELECT code FROM categories) GROUP BY 1;

-- Aliases by code and source
SELECT c.code, a.source, count(*)
FROM category_aliases a JOIN categories c ON c.id = a.category_id
GROUP BY 1, 2 ORDER BY 1, 2;

-- What normalisation actually rewrote
SELECT category, source_category, count(*) FROM complaints
WHERE source_category IS NOT NULL GROUP BY 1, 2 ORDER BY 3 DESC;

-- Ingestion-side category rejections
SELECT * FROM ingestion_rejections WHERE field = 'category';

-- Default department per category (V19)
SELECT c.code, d.name AS default_department
FROM categories c LEFT JOIN departments d ON d.id = c.default_department_id
ORDER BY c.sort_order;
```
