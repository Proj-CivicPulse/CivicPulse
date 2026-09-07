-- Human-readable complaint reference numbers: CP-{year}-W{ward code}-{00412}.
--
-- This is the resident's receipt and the only handle they have on a report, so
-- it must be unique, stable, and gap-free.
--
-- WHY A COUNTER TABLE RATHER THAN A SEQUENCE
--   A Postgres SEQUENCE is non-transactional: a rolled-back submission burns a
--   number permanently, so residents would see gaps in what is presented as a
--   register. Per-ward sequences would also need CREATE SEQUENCE at runtime
--   when a ward is added, and docs/service-boundaries.md decision 3 keeps all
--   DDL inside Flyway.
--   A generated column cannot work at all: GENERATED ALWAYS AS requires an
--   IMMUTABLE expression over the row's own columns, so it can neither count
--   sibling rows nor read wards.code.
--   Application-side MAX(seq)+1 duplicates under concurrent inserts at READ
--   COMMITTED.
--
-- The upsert in ReferenceNumberService increments this table inside the
-- complaint's own transaction. A second concurrent inserter blocks on the
-- conflicting row lock, then re-reads and increments — correct without
-- SERIALIZABLE, with contention scoped to a single (ward, year).

CREATE TABLE complaint_reference_counters (
    ward_id  BIGINT  NOT NULL,
    year     INTEGER NOT NULL,
    next_seq BIGINT  NOT NULL,
    PRIMARY KEY (ward_id, year),
    CONSTRAINT fk_reference_counters_ward FOREIGN KEY (ward_id) REFERENCES wards (id)
);

ALTER TABLE complaints ADD COLUMN reference_no VARCHAR(32);

-- Backfill deterministically, so re-running this against a restored dump
-- produces byte-identical references rather than reshuffling receipts.
WITH numbered AS (
    SELECT c.id,
           w.code AS ward_code,
           EXTRACT(YEAR FROM c.created_at)::int AS yr,
           ROW_NUMBER() OVER (
               PARTITION BY c.ward_id, EXTRACT(YEAR FROM c.created_at)
               ORDER BY c.created_at, c.id
           ) AS seq
    FROM complaints c
    JOIN wards w ON w.id = c.ward_id
)
UPDATE complaints c
SET reference_no = 'CP-' || n.yr || '-W' || n.ward_code || '-' || LPAD(n.seq::text, 5, '0')
FROM numbered n
WHERE c.id = n.id;

-- Seed each counter past whatever the backfill consumed, so the first runtime
-- allocation cannot collide with a backfilled row.
INSERT INTO complaint_reference_counters (ward_id, year, next_seq)
SELECT c.ward_id, EXTRACT(YEAR FROM c.created_at)::int, COUNT(*)
FROM complaints c
GROUP BY c.ward_id, EXTRACT(YEAR FROM c.created_at);

ALTER TABLE complaints ALTER COLUMN reference_no SET NOT NULL;

-- The backstop: any future bug in the allocation logic becomes a loud failure
-- rather than two residents silently sharing a reference number.
ALTER TABLE complaints ADD CONSTRAINT uq_complaints_reference_no UNIQUE (reference_no);
