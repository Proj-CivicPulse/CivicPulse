# Release Readiness Audit

**Audited:** 2026-09-17 · **Re-audited after remediation:** 2026-09-19
**Target:** backend-spring, backend-node, frontend
**Deployed and verified:** 2026-09-19
**Verdict:** **SHIPPED.** Both blockers are cleared; every smoke test in §5
passes against production. The original verdict, kept for the record, was "GO —
and deploy urgently", because the schema had shipped ahead of the code and
production was serving retired placeholder wards. That is resolved — see §8.

Statuses are used strictly:

| Status | Means |
|---|---|
| **RESOLVED** | Implemented, and verified by a command whose output is recorded here |
| **MITIGATED** | Materially improved; a named residual gap remains |
| **DOCUMENTED** | No code change; the decision or contract is written down and approved |
| **DEFERRED** | Deliberately out of scope, with a recorded decision |
| **OPEN** | Not addressed |

---

## 1. Issue register

### ISSUE-01 — Requirements docs do not match matching rules
- **Status:** RESOLVED (DOCUMENTED)
- **Files:** `docs/matching-contract.md` (new); `backend-node/src/services/matching.service.ts`; `service/incident/NaiveIncidentGrouper.java`; `README.md`
- **Proof:** `grep -rni "10.minute\|ten.minute" . --exclude-dir={node_modules,target,.git}` → **no hits anywhere in the repository.** The rule the audit describes has never existed here.
- **Residual risk:** The audit document itself still asserts the rule. Anyone reading it rather than `matching-contract.md` will be misled.
- **Blocks deploy:** NO

### ISSUE-02 — Copilot / hotspot stubs presented as complete
- **Status:** RESOLVED
- **Files:** `frontend/src/components/landing/WhyDifferent.tsx`; `HowItWorks.tsx`; `README.md`
- **Proof:** `cd frontend && npx tsc --noEmit && npm run build` → clean. The API was already honest (`notImplemented` → 501); only marketing copy overstated.
- **Residual risk:** Low. Copy can drift again; nothing tests it.
- **Blocks deploy:** NO

### ISSUE-03 — Category governance missing
- **Status:** RESOLVED
- **Files:** `V11__category_registry.sql`, `V14__category_registry_quarantine_followup.sql`; `entity/Category.java`, `entity/CategoryAlias.java`; `repository/CategoryRepository.java`, `CategoryAliasRepository.java`; `service/category/CategoryNormalizer.java`, `CategoryService.java`; `dto/category/CategoryDto.java`; `controller/CategoryController.java`; `config/SecurityConfig.java`; `service/complaint/ComplaintService.java`; `entity/Complaint.java`, `entity/Incident.java`
- **Proof:** `./mvnw test -Dtest='CategoryNormalizerTest,CategoryServiceTest'` → 26 pass. Live: `POST /complaints` with `"category":"interpretive dance"` → `400 category must be one of: pothole, streetlight, garbage, water, drainage, footpath, noise, other`.
- **Residual risk:** Rejecting unknown categories is a **behaviour change**. Any API client not sending a registry code now gets 400. The shipped frontend sends codes read from `/categories`, so no known client is affected.
- **Blocks deploy:** NO

### ISSUE-04 — Exact category matching too strict
- **Status:** RESOLVED (via ISSUE-03)
- **Files:** as ISSUE-03
- **Proof:** Live: `POST /complaints` with `"category":"Solid Waste"` → stored `category=garbage`, `source_category='Solid Waste'`.
- **Residual risk:** Normalisation fixes *spelling*, not *mis-categorisation*. A blocked drain filed as `other` still cannot group with drains. Tracked as open question 5 in `matching-contract.md`.
- **Blocks deploy:** NO

### ISSUE-05 — Ward data is placeholder
- **Status:** RESOLVED
- **Files:** `V13__bbmp_ward_boundaries.sql` (generated, 1.4 MB); `scripts/generate-ward-migration.mjs`; `scripts/verify-ward-import.mjs`; `entity/Ward.java`; `repository/WardRepository.java`; `service/ward/WardService.java`
- **Proof:** `node scripts/verify-ward-import.mjs` → **ALL CHECKS PASSED** (243 wards, all boundaries valid, all 243 representative points resolve to exactly one ward, Mysuru/Chennai/Null Island resolve to none).
- **Residual risk:** The 2022 delimitation is contested and may be superseded by the Greater Bengaluru Authority reorganisation. `zone` is NULL for all 243 — the source carries none. `dataset_version` / `effective_from` exist so a re-import is a new migration.
- **Blocks deploy:** NO — but see **BLOCKER-1**, which is about deployment *ordering*, not the data.

### ISSUE-06 — Nearest-centroid ward assignment
- **Status:** RESOLVED
- **Files:** `service/ward/PostGisWardResolver.java` (new); `CentroidWardResolver.java`; `config/AppProperties.java`; `resources/application.yaml`; `.env.example`; `deploy-secrets/1-railway-backend-spring.env`
- **Proof:** `./mvnw test -Dtest='WardResolverSelectionTest,PostGisWardResolverTest'` → 8 pass. **Live discriminating probe** — at coordinates where the two methods disagree, the running app returned the containment answer:

  | point | `ST_Contains` | nearest centroid | live `/wards/resolve` |
  |---|---|---|---|
  | 13.128979, 77.586145 | 1 Kempegowda | **2** Chowdeswari | **1 Kempegowda** ✅ |
  | 12.973256, 77.510895 | 48 Jnana Bharathi | **46** Sir M.V. | **48 Jnana Bharathi** ✅ |
- **Residual risk:** None material.
- **Blocks deploy:** NO — but `WARD_RESOLVER` must be set; see **BLOCKER-2**.

### ISSUE-07 — Priority scores go stale
- **Status:** RESOLVED
- **Files:** `V12__incident_priority_computed_at.sql`; `job/PriorityRefreshJob.java` (new); `service/incident/IncidentAttachmentService.java`; `entity/Incident.java`; `dto/incident/IncidentDto.java`; `repository/IncidentRepository.java`; `config/AppProperties.java`; `application.yaml`; `application-test.yaml`
- **Proof:** `./mvnw test -Dtest=PriorityRefreshJobTest` → 4 pass. **And observed live** — the audit found 30 of 31 open incidents had `priority_computed_at = NULL`, meaning the job had never actually fired in any run. Executed deliberately with a shortened interval:
  ```
  PriorityRefreshJob : Priority refresh: 31 recomputed, 0 failed, 31 older than ...
  ```
  Post-conditions checked immediately after: **31/31** open incidents now have `priority_computed_at`; `incident_match_log` still **172 rows, 0 new** (the sweep did not fabricate match rows); **0** resolved/closed incidents touched; **0** complaints modified.
- **Residual risk:** Member *status* is not an input — an incident with ten reports, eight individually resolved, still scores as ten. Deliberate; belongs to the Phase 8 calibration pass.
- **Blocks deploy:** NO

### ISSUE-08 — Priority algorithm not calibrated
- **Status:** RESOLVED (evaluated; explicit decision recorded)
- **Files:** `PriorityCalibrationTest.java` (new); `docs/priority-evaluation.md` (generated)
- **Proof:** `./mvnw test -Dtest=PriorityCalibrationTest` → 2 pass, report regenerated. Contains a **DECISION** section: ship 40/30/20/10 and bands 3.0/5.5/7.5 unchanged, with accepted defect, revisit trigger (200 labelled incidents or first officer complaint) and the first change to try (age saturation 14 → 30–45 days).
- **Residual risk:** Weights remain unvalidated against real data. Two measured defects accepted: age saturates for ~77% of incidents (acting as a constant offset), growth is inert for ~62%.
- **Blocks deploy:** NO

### ISSUE-09 — O(N²) geographic spread
- **Status:** RESOLVED (measured; decision is *do not optimise*)
- **Files:** `PriorityServiceBenchmarkTest.java` (new)
- **Proof:** `./mvnw test -Dtest=PriorityServiceBenchmarkTest` → 3 pass. Real max cluster = **17 members** (0.13 ms). At 500 (≈30× real max) p99 = **13.2 ms**, inside the asserted 50 ms budget. Quadratic confirmed but only bites at ~5000.
- **Residual risk:** Budget asserted at 500 members. An incident above ~1000 would slow the refresh sweep. Nothing currently bounds incident size.
- **Blocks deploy:** NO

### ISSUE-10 — No ingestion validation gate
- **Status:** RESOLVED
- **Files:** `V15__ingestion_gate.sql`; `dto/ingest/ExternalComplaint.java`, `IngestionReport.java`; `service/ingest/RejectionReason.java`, `IngestionValidator.java`, `IngestionRecordProcessor.java`, `IngestionService.java`; `controller/IngestionController.java`; `entity/IngestionBatch.java`, `IngestionRecord.java`; `enums/IngestionOutcome.java`; `repository/IngestionBatchRepository.java`, `IngestionRecordRepository.java`, `ComplaintRepository.java`; `entity/Complaint.java`; `scripts/verify-ingestion.mjs`
- **Proof:** `./mvnw test -Dtest='IngestionValidatorTest,IngestionServiceTest'` → 29 pass. `node scripts/verify-ingestion.mjs` against the running app → **ALL CHECKS PASSED**.
- **Residual risk:** No feed is configured, so this subsystem is unexercised by any real publisher. The `created_at` conflation that made a first import dangerous is fixed (§3); the remaining unknown is how a real publisher's data behaves, which only a real publisher can answer.
- **Blocks deploy:** NO

### ISSUE-11 — External / internal ward ID governance
- **Status:** RESOLVED
- **Files:** `V13` (`ward_external_ids` view); `service/ingest/IngestionValidator.java`
- **Proof:** `verify-ward-import.mjs` → 243 imported wards each map to a distinct source code; **45 unmapped LGD codes reported, not invented**. `IngestionValidatorTest.mapsWardByAnyKnownIdentifier` proves a feed may quote our ward number, the KGIS code, or the LGD code.
- **Residual risk:** The crosswalk is a **view over current ward rows**, not a versioned table. Mappings across a future delimitation would not be retained historically.
- **Blocks deploy:** NO

### ISSUE-12 — Frontend category list hardcoded
- **Status:** RESOLVED
- **Files:** `frontend/src/services/category.service.ts` (new); `pages/SubmitComplaint.tsx`; `pages/IncidentDashboard.tsx`; `components/dashboard/WardRail.tsx`, `IncidentDetail.tsx`; `lib/queryKeys.ts`
- **Proof:** `npx tsc --noEmit && npm run lint && npm run build` → clean. Live `GET /categories` returns all 8 canonical categories.
- **Residual risk:** `FALLBACK_CATEGORIES` can drift from the registry. It contains only codes that resolve server-side, so drift degrades choice, never correctness.
- **Blocks deploy:** NO

### ISSUE-13 — Node matcher lacks spatial / temporal bounds
- **Status:** RESOLVED (DOCUMENTED decision)
- **Files:** `docs/matching-contract.md` §2; `matching.service.ts`; `NaiveIncidentGrouper.java`
- **Proof:** `matching.linkage.test.ts` → a perfect semantic match in another ward or another category is **not** a candidate; a resolved incident is **not** a candidate.
- **Residual risk:** Distance and time remain unbounded in the semantic matcher. Accepted and documented; unvalidated against officer judgement.
- **Blocks deploy:** NO

### ISSUE-14 — Single-linkage chaining
- **Status:** RESOLVED (demonstrated; explicit product decision)
- **Files:** `backend-node/src/services/matching.linkage.test.ts` (new); `docs/matching-contract.md` §4
- **Proof:** `npm test` with `TEST_DATABASE_URL` → 5 linkage tests against real pgvector. Chaining reproduced: B joins A at 0.766, C then joins the same incident at 0.766 *via B* while being **0.174** similar to A.
- **Residual risk:** Accepted over-grouping. Documented rationale: over-grouping is visible to an officer; under-grouping is invisible and is the failure the feature exists to prevent.
- **Blocks deploy:** NO

### ISSUE-15 — Seed data insufficient
- **Status:** MITIGATED
- **Files:** `scripts/seed-dev-data.mjs`; `matching.linkage.test.ts`; `PriorityCalibrationTest.java`
- **Proof:** Seed rotates through accepted alias spellings; calibration corpus is 2,000 incidents across 243 wards and 8 categories including 5% with no coordinates.
- **Residual risk:** **No labelled ground-truth fixture set exists.** Phase 8's precision/recall/F1 still needs human labels; nothing here substitutes.
- **Blocks deploy:** NO

### ISSUE-16 — Photo upload unresolved
- **Status:** DEFERRED (contract decided and approved)
- **Files:** `docs/photo-upload-contract.md` (new); `docs/endpoints.md`
- **Proof:** Contract marked `STATUS: DEFERRED`; `endpoints.md` decision closed; submit form still says upload unavailable.
- **Residual risk:** **`CreateComplaintRequest.photoUrl` is still accepted and stored verbatim.** Verified inert today — `grep -rn "photoUrl" frontend/src --include=*.tsx` finds **no render site**, so the stored value is never displayed or fetched. It becomes live the moment any UI renders it.
- **Blocks deploy:** NO — it is a hard prerequisite of the photo feature, not of this release.

---

## 2. The 20 specific verifications

| # | Check | Result | Evidence |
|---|---|---|---|
| 1 | Upstream report time stored **separately** from `@CreationTimestamp` | ✅ **YES** *(fixed 2026-09-19)* | V16 adds `complaints.reported_at`, NOT NULL, backfilled exactly. `created_at` is no longer rewritten and `backdateCreatedAt` is gone. `ReconcileOrderingTest.theTwoTimestampsStayIndependent` |
| 2 | Imported complaints preserve original report time | ✅ YES | `verify-ingestion.mjs`: `created_at` = `2026-09-10 08:30:00`, not the import moment |
| 3 | V14/V15 compatible with the **currently deployed** app | ⚠️ **V14/V15 yes — V13 NO** | V14/V15 are purely additive. V13 broke the deployed app: see **BLOCKER-1** |
| 4 | `WARD_RESOLVER=postgis` read at runtime | ✅ YES | `WardResolverSelectionTest` (5 tests) + live discriminating probe |
| 5 | `PostGisWardResolver` performs `ST_Contains` | ✅ YES | `WardRepository.findContaining` native query; live probe returned containment answers |
| 6 | Centroid resolver only as explicit fallback | ✅ YES | `@ConditionalOnProperty(havingValue="centroid")`, no `matchIfMissing`; test asserts the bean is **absent** under postgis |
| 7 | All 243 BBMP wards imported | ✅ YES | `verify-ward-import.mjs`: 243, codes 1–243 no gaps, all unique |
| 8 | Invalid geometries repaired, area change reported | ✅ YES *(caveat closed 2026-09-19)* | 5 repaired, **0.0000%** change; migration aborts above 0.5%. Now **persisted**: V17 creates `dataset_validations` and records 5 checks for the ward import; the generator writes its own rows on any future re-import |
| 9 | GeoJSON extra-ring interpretation correct | ✅ YES | Wards 1/34/206 kept 2 parts, ward 105 kept **4**, ward 6 kept its enclave as a hole |
| 10 | Missing LGD codes stay null and are reported | ✅ YES | 45 null, surfaced by `ward_external_ids WHERE lgd_unmapped` |
| 11 | Ingestion idempotent on `source` + `sourceRecordId` | ✅ YES | Re-running the identical batch: `accepted: 0`, table did not grow |
| 12 | Each record in its **own** transaction | ✅ YES | `IngestionServiceTest.oneThrowingRecordDoesNotStopTheBatch` — record 5 of 10 throws, all 10 still processed; plus a structural assertion that `process`/`recordFailure` are `REQUIRES_NEW` on a **separate bean** and the batch loop holds no transaction |
| 13 | Rejected records retain **all** reasons | ✅ YES | Multiply-broken record recorded **6** reasons, not 1; `ingestion_rejections` view groups 9 distinct faults |
| 14 | `ObjectMapper` config introduces no duplicate beans | ✅ YES | **No `@Bean ObjectMapper` exists anywhere.** Fixed during this audit: the hash mapper was on Jackson 2, present only *transitively* via `jjwt-jackson` — now on the app's own Jackson 3, as a private static field, not a bean |
| 15 | Photo upload fully documented or explicitly deferred | ✅ YES *(defect closed 2026-09-19)* | `photo-upload-contract.md` marked `STATUS: DEFERRED`. The caller-supplied `photoUrl` is no longer accepted on `POST /complaints` at all — field removed from the DTO, the service and the frontend request type |
| 16 | Node matcher spatial/temporal responsibilities documented | ✅ YES | `matching-contract.md` §2 — ward/category/status constrained, distance/time explicitly **not** |
| 17 | 10-minute rule implemented or removed | ✅ N/A | Never existed in this codebase; stated in `matching-contract.md` §1 |
| 18 | Priority refresh behaviour explicitly defined | ✅ YES | `service-boundaries.md` decision 1; config in `application.yaml`; **observed running** |
| 19 | Calibration report contains a **decision** | ✅ YES | `priority-evaluation.md` → `## DECISION`, with scope, accepted defect, revisit trigger, first change to try |
| 20 | Chaining has an explicit **product decision** | ✅ YES | `matching-contract.md` §4 → `DECISION: keep single-linkage for this release` |

---

## 3. Remediation since the first audit (2026-09-19)

Everything the first pass left open and actionable has been closed. What
remains open is listed in §7 and is either someone else's action or new feature
work.

**`created_at` conflation — fixed.** V16 adds `complaints.reported_at`
(NOT NULL, backfilled from `created_at`, which was exact because every existing
row was a website submission). `created_at` is never rewritten;
`backdateCreatedAt` is deleted. `PriorityService` counts growth by
`reported_at`; the incident chronology orders by it.

**Reconcile starvation — fixed.** The sweep orders by `id`, which is arrival
order and cannot be influenced by anything a caller sends.
`ReconcileOrderingTest` runs against the real database and proves a complaint
filed five minutes ago is processed before an import carrying a six-month-old
`reported_at`, and that a page of five rows with descending reported times still
comes back in arrival order.

**Area-change validation not persisted — fixed.** V17 creates
`dataset_validations` — a generic per-check record with `status`
(PASS/WARN/FAIL), `observed`, `threshold` and `detail` — and backfills the ward
import's five checks. Four are re-derived from current rows rather than
remembered; the repair-drift row is transcribed from the V13 run and says so,
because the pre-repair geometry no longer exists. The generator now writes these
rows itself, so a future re-import records its own quality.

Operational query:
```sql
SELECT check_name, status, observed, threshold, unit
FROM dataset_validations WHERE status <> 'PASS';
-- currently one row: lgd-code-coverage, WARN, 45 wards without an LGD code
```

**`photoUrl` hole — closed.** Removed from `CreateComplaintRequest`,
`ComplaintService.create` and the frontend's `CreateComplaintInput`. It stays on
the response shape, since that column is where the server-issued upload flow
will write a key it minted itself.

**A timezone bug the split exposed — fixed.** `BackendSpringApplication` pinned
the JVM to UTC in a `@PostConstruct`, which runs *after* Hibernate resolves its
default zone. `@CreationTimestamp` therefore wrote the machine's local wall
clock into a zoneless column and `Wire.timestamp()` stamped a `Z` on it: on an
IST machine every `created_at` was **5.5 hours in the future**, reported that way
to every client, with nothing failing. It was invisible until `reported_at`
landed a correct UTC value one second apart from a wrong one. Enforcement moved
to a `static` initialiser, which runs at class load. Verified: a fresh
complaint's `reported_at`, `created_at` and the database's own
`now() at time zone 'UTC'` now agree within one second.

Rows written before the fix keep their old stamps and are left alone
deliberately — some timestamps in those tables came from SQL `NOW()` and were
always correct, so a uniform shift would corrupt the rows that were right. They
are internally consistent (V16 copied `created_at` into `reported_at`), and the
drift is hours against a formula whose shortest window is 24 hours.

**Generator can no longer clobber an applied migration.** V13 has been applied
to real databases, and Flyway checksums applied migrations — silently
regenerating over it would have bricked startup everywhere it had already run.
The generator now refuses to overwrite an existing file and tells you to pass a
new migration name above V17.

## 4. Deployment blockers

### BLOCKER-1 — Production is degraded *right now*: schema is ahead of code
**Severity: Critical. Live. Deploying is the fix.**

The migrations were applied to the production Neon database on 2026-09-14, but
Railway still runs pre-V11 code. Observed today:

```
GET https://kind-caring-production-b166.up.railway.app/wards/resolve?lat=12.9250&long=77.5938
→ {"id":"3","code":"LEGACY-17","name":"Ward 17 (retired placeholder)",...}

GET .../wards      → 247 wards, first is "Ward 1 (retired placeholder)"
GET .../categories → 401 (endpoint does not exist in the deployed build)
```

The old `CentroidWardResolver` calls `findAll()`, which now returns the four
retired placeholders alongside the 243 real wards — and their centroids sit
exactly on the old demo coordinates, so they win. **Any complaint submitted
through production right now is filed into a retired ward and issued a reference
number reading `CP-2026-WLEGACY-17-000NN`.**

**Damage so far: none.** Verified — 0 complaints created since V13, 0 on
placeholder wards, 0 LEGACY reference numbers. This is a demo deployment with no
live traffic. The exposure is real; the harm has not occurred.

**Action: deploy backend-spring. Nothing else removes this.**

### BLOCKER-2 — `WARD_RESOLVER` must be set to `postgis` in Railway
Currently `centroid` in `deploy-secrets/1-railway-backend-spring.env` (updated
locally) — the platform still holds the old value. With the new code and
`centroid`, the app starts and works, but against 243 real centroids rather than
polygons: ISSUE-06's fix is inert. Not app-breaking, but it silently discards the
main benefit of this release.

**Action: set `WARD_RESOLVER=postgis` in Railway before or with the deploy.**

### Not blockers (verified)
- **PostGIS extension** — already created on the production database (same Neon
  instance the migrations ran against).
- **Deploy order frontend vs backend** — safe either way. Old frontend sends
  hardcoded codes, all of which are valid registry codes. New frontend against
  old backend gets 401 on `/categories` and falls back to `FALLBACK_CATEGORIES`.
- **backend-node** — unchanged except comments and a new test file. No deploy
  strictly required, though redeploying costs nothing.

---

## 5. Post-deployment smoke tests

Run in order, immediately after deploy. `$SPRING` = the backend-spring URL.

```bash
# 1. Service is up
curl -s $SPRING/health                                    # {"status":"ok"}

# 2. BLOCKER-1 is cleared — no retired wards are offered
curl -s $SPRING/wards | grep -c "retired placeholder"     # MUST be 0
curl -s $SPRING/wards | node -e "let d='';process.stdin.on('data',c=>d+=c)
  .on('end',()=>console.log(JSON.parse(d).length))"       # MUST be 243, not 247

# 3. BLOCKER-2 is cleared — containment, not nearest centroid
curl -s "$SPRING/wards/resolve?lat=12.973256&long=77.510895"
#   MUST be code 48 (Jnana Bharathi). Code 46 means WARD_RESOLVER is still centroid.

# 4. Out-of-area still refuses rather than guessing
curl -s -o /dev/null -w "%{http_code}\n" \
  "$SPRING/wards/resolve?lat=12.2958&long=76.6394"        # MUST be 404

# 5. Category registry is public and complete
curl -s $SPRING/categories | node -e "let d='';process.stdin.on('data',c=>d+=c)
  .on('end',()=>console.log(JSON.parse(d).length))"       # MUST be 8

# 6. Alias normalisation on the real write path
curl -s -X POST $SPRING/complaints -H 'Content-Type: application/json' \
  -d '{"description":"Smoke test: garbage at the corner","category":"Solid Waste",
       "lat":12.9250,"long":77.5938}'
#   MUST return "category":"garbage" and a reference number WITHOUT "LEGACY"
#   >>> DELETE this complaint afterwards. <<<

# 7. Unknown category is refused
curl -s -o /dev/null -w "%{http_code}\n" -X POST $SPRING/complaints \
  -H 'Content-Type: application/json' \
  -d '{"description":"x","category":"interpretive dance","lat":12.925,"long":77.5938}'
#   MUST be 400

# 8. Frontend submit form renders categories from the API (manual)
#    Open the Vercel URL → Report a problem → the picker MUST list 8 options
#    including "Damaged or blocked footpath" and "Excessive noise".
```

**Then, ~10 minutes later**, confirm the priority sweep is alive in production —
it is the one component whose failure is completely silent:

```sql
SELECT count(*) FILTER (WHERE priority_computed_at IS NULL) AS never_computed
FROM incidents WHERE status IN ('OPEN','IN_PROGRESS');
-- Should fall to 0 within two sweep intervals (default 5 min each).
-- If it stays at 31, PRIORITY_REFRESH_ENABLED is false or the scheduler is dead.
```

And confirm the sweep is not corrupting the Phase 8 dataset:

```sql
SELECT count(*) FROM incident_match_log
WHERE created_at > now() - interval '1 hour';   -- MUST stay 0 absent real matching
```

---

## 6. Rollback plan

**Code rollback is safe. Schema rollback is not, and should not be needed.**

### Rolling back the application only (the normal lever)
Redeploy the previous Railway image. Old code against the V15 schema starts
cleanly — Hibernate's `ddl-auto=validate` checks that *mapped* columns exist, not
that every column is mapped, so the added columns and tables are ignored.

**But rolling back re-creates BLOCKER-1.** You return to retired placeholder
wards being offered and resolved into. Roll back only for a failure worse than
that, and treat it as a hold, not a resting state.

### Rolling back the schema (last resort)
V11–V15 have **no down-migrations**, deliberately — V13 renamed placeholder ward
codes and re-homed every complaint, and a scripted reverse would be more
dangerous than the forward path.

The lever is **Neon point-in-time restore / branch restore** to before
`2026-09-14T07:07:04Z` (when V13 was applied). This is currently cheap: **zero
complaints have been created since**, so a restore to that point loses no citizen
data. That property expires the moment real traffic arrives.

### Before deploying
1. **Take a Neon branch** from `main` (instant, copy-on-write, free on the
   starter plan). That branch is the rollback target and costs nothing to keep.
2. Record the current `flyway_schema_history` max version (**15**).
3. Note the baseline: **167 complaints, 31 incidents, 172 match-log rows.**

### Rollback decision rule
| Symptom | Action |
|---|---|
| Smoke test 2 or 3 fails | Fix config (`WARD_RESOLVER`) and redeploy — do **not** roll back |
| Smoke test 6 or 7 fails | Roll back the app; the registry write path is wrong |
| App will not start | Roll back the app; schema is compatible, so this is a code fault |
| Ward data is visibly wrong to an officer | Neon branch restore; re-import after fixing the generator |

---

## 7. Final recommendation

**GO. Deploy backend-spring and the frontend now.**

The rationale is not that the work is complete — it is that **the riskiest state
is the current one.** The database is three days ahead of the code, production is
serving retired placeholder wards from a schema that no longer matches the
application, and every hour in that state is an hour in which a submitted
complaint gets a `LEGACY` ward and a reference number nobody can explain.
Deploying closes that.

Verification standing behind this:

| | |
|---|---|
| backend-spring | **172 tests pass** (including the context test, which applies all 17 migrations and validates every entity) |
| backend-node | **23 tests pass**, incl. 5 against real pgvector; typechecks; lints clean |
| frontend | typechecks, lints, builds |
| `verify-ward-import.mjs` | ALL CHECKS PASSED |
| `verify-ingestion.mjs` | ALL CHECKS PASSED, against the running app |
| Priority sweep | observed executing: 31 recomputed, 0 match-log rows written |
| Database | restored to baseline 167 / 31; no verification residue |

`ReferenceNumberServiceTest` is the one test not run — it needs concurrent
database access and times out against remote Neon from this machine. It is
unmodified by this work.

**Conditions on the GO:**
1. Set `WARD_RESOLVER=postgis` in Railway **with** the deploy (BLOCKER-2).
2. Take the Neon branch first (§6).
3. Run §5 smoke tests immediately; run the priority-sweep check 10 minutes later.
4. **Do not render `photo_url`** anywhere until the photo contract ships. The
   field can no longer be set from outside, but the column is still displayed
   nowhere by design, and that is the property to guard in review.
5. When the first external feed is onboarded, run `verify-ingestion.mjs`
   against it before trusting a full import, and watch `unchanged` on the second
   run — it should be most of the batch.

---

## 8. Post-deployment verification — 2026-09-19

Run against production after the deploy and the `WARD_RESOLVER` change.

### BLOCKER-1 — CLEARED

| Check | Result |
|---|---|
| `/wards` | **243**, zero `LEGACY` / "retired placeholder" entries |
| `/categories` | **200**, all 8 codes |
| Unknown category on `POST /complaints` | **400** naming the accepted codes |

### BLOCKER-2 — CLEARED (on the second attempt)

The first check after deploy **failed**: both discriminating probes returned the
nearest-centroid answer, meaning `WARD_RESOLVER=postgis` was not in effect in
the running process. It took a redeploy to pick the variable up. Worth
remembering — on Railway, editing a variable does not restart a running
container, and the failure is silent: every complaint still gets a ward, just
the approximate one.

After the redeploy:

| Point | PostGIS | Centroid | Production |
|---|---|---|---|
| 12.973256, 77.510895 | 48 Jnana Bharathi | 46 Sir M.V. | **48** ✅ |
| 13.128979, 77.586145 | 1 Kempegowda | 2 Chowdeswari | **1** ✅ |

Out-of-area refuses everywhere it should: Mysuru, Chennai, Null Island and a
point just north of the boundary all **404**.

### Write path

One complaint submitted with the alias `"Solid Waste"` and a hostile
`photoUrl`, then deleted:

```
category     garbage              (alias normalised)
referenceNo  CP-2026-W48-00001    (real ward code, no LEGACY)
wardId       538  -> ward 48 Jnana Bharathi, which is what ST_Contains says
photoUrl     null                 (the hole is closed — the hostile URL was dropped)
reportedAt   2026-09-19T07:28:31.044Z
createdAt    2026-09-19T07:28:31.379Z   (0.33 s apart; 16 s behind the DB's own UTC clock)
matchingStat MATCHED              (the SEMANTIC matcher ran, not the naive fallback)
```

The timestamp pair is the timezone fix confirmed in production: before it,
`created_at` would have been 5.5 hours ahead of the database's own clock.

### Background jobs and services

| Check | Result |
|---|---|
| Open incidents lacking `priority_computed_at` | **0 of 31** — the sweep is running |
| `incident_match_log` rows in the last hour | **0** — the sweep writes no matching decisions |
| backend-node `/health` | ok |
| Frontend | 200; `/api/core/categories` returns the registry through the same-origin proxy |
| `/api/ai/health` | 200 |

### Residue

None. Production is back to its pre-verification baseline: **167 complaints, 31
incidents**, zero sourced rows, zero empty incidents, zero complaints missing
`reported_at`.
