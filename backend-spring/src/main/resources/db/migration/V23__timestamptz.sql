-- Every timestamp column becomes TIMESTAMPTZ (data-quality-rules.md §2).
--
-- Until now the columns were zoneless TIMESTAMP(6), and "the values are UTC"
-- was a promise kept by the Spring process alone (the JVM is pinned to UTC).
-- Any other writer was outside it: backend-node writes updated_at = now(),
-- which a zoneless column stores in the SESSION's zone, and node-postgres
-- parses a zoneless value as the Node process's local time. With TIMESTAMPTZ
-- the database itself knows each value is an instant, so every client reads
-- the same moment whatever its zone.
--
-- USING col AT TIME ZONE 'UTC' reinterprets each stored wall-clock value as
-- UTC, which is what Spring wrote (and what node's now() wrote on a UTC
-- server, which both deployed databases are). No value changes instant.
--
-- The ingestion_rejections view reads ingestion_records.created_at, and
-- Postgres will not change the type of a column a view uses, so the view is
-- dropped and recreated unchanged around the ALTER.

DROP VIEW ingestion_rejections;

ALTER TABLE auth_tokens
    ALTER COLUMN consumed_at TYPE TIMESTAMPTZ(6) USING consumed_at AT TIME ZONE 'UTC',
    ALTER COLUMN created_at  TYPE TIMESTAMPTZ(6) USING created_at  AT TIME ZONE 'UTC',
    ALTER COLUMN expires_at  TYPE TIMESTAMPTZ(6) USING expires_at  AT TIME ZONE 'UTC';

ALTER TABLE categories
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(6) USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ(6) USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE category_aliases
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(6) USING created_at AT TIME ZONE 'UTC';

ALTER TABLE complaints
    ALTER COLUMN created_at  TYPE TIMESTAMPTZ(6) USING created_at  AT TIME ZONE 'UTC',
    ALTER COLUMN matched_at  TYPE TIMESTAMPTZ(6) USING matched_at  AT TIME ZONE 'UTC',
    ALTER COLUMN reported_at TYPE TIMESTAMPTZ(6) USING reported_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at  TYPE TIMESTAMPTZ(6) USING updated_at  AT TIME ZONE 'UTC';

ALTER TABLE dataset_validations
    ALTER COLUMN checked_at TYPE TIMESTAMPTZ(6) USING checked_at AT TIME ZONE 'UTC';

ALTER TABLE departments
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(6) USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ(6) USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE geocode_cache
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(6) USING created_at AT TIME ZONE 'UTC';

ALTER TABLE incident_match_log
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(6) USING created_at AT TIME ZONE 'UTC';

ALTER TABLE incidents
    ALTER COLUMN created_at           TYPE TIMESTAMPTZ(6) USING created_at           AT TIME ZONE 'UTC',
    ALTER COLUMN first_reported_at    TYPE TIMESTAMPTZ(6) USING first_reported_at    AT TIME ZONE 'UTC',
    ALTER COLUMN last_reported_at     TYPE TIMESTAMPTZ(6) USING last_reported_at     AT TIME ZONE 'UTC',
    ALTER COLUMN priority_computed_at TYPE TIMESTAMPTZ(6) USING priority_computed_at AT TIME ZONE 'UTC',
    ALTER COLUMN resolved_at          TYPE TIMESTAMPTZ(6) USING resolved_at          AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at           TYPE TIMESTAMPTZ(6) USING updated_at           AT TIME ZONE 'UTC';

ALTER TABLE ingestion_batches
    ALTER COLUMN created_at  TYPE TIMESTAMPTZ(6) USING created_at  AT TIME ZONE 'UTC',
    ALTER COLUMN finished_at TYPE TIMESTAMPTZ(6) USING finished_at AT TIME ZONE 'UTC',
    ALTER COLUMN started_at  TYPE TIMESTAMPTZ(6) USING started_at  AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at  TYPE TIMESTAMPTZ(6) USING updated_at  AT TIME ZONE 'UTC';

ALTER TABLE ingestion_records
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(6) USING created_at AT TIME ZONE 'UTC';

ALTER TABLE recommendation_feedback
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(6) USING created_at AT TIME ZONE 'UTC';

ALTER TABLE refresh_tokens
    ALTER COLUMN expires_at TYPE TIMESTAMPTZ(6) USING expires_at AT TIME ZONE 'UTC',
    ALTER COLUMN issued_at  TYPE TIMESTAMPTZ(6) USING issued_at  AT TIME ZONE 'UTC',
    ALTER COLUMN revoked_at TYPE TIMESTAMPTZ(6) USING revoked_at AT TIME ZONE 'UTC';

ALTER TABLE users
    ALTER COLUMN created_at        TYPE TIMESTAMPTZ(6) USING created_at        AT TIME ZONE 'UTC',
    ALTER COLUMN email_verified_at TYPE TIMESTAMPTZ(6) USING email_verified_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at        TYPE TIMESTAMPTZ(6) USING updated_at        AT TIME ZONE 'UTC';

ALTER TABLE wards
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(6) USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ(6) USING updated_at AT TIME ZONE 'UTC';

-- Recreated exactly as V15 defined it.
CREATE VIEW ingestion_rejections AS
SELECT r.source,
       reason.value ->> 'field'                     AS field,
       reason.value ->> 'code'                      AS code,
       COUNT(*)                                     AS occurrences,
       MAX(r.created_at)                            AS last_seen,
       (ARRAY_AGG(r.source_record_id ORDER BY r.created_at DESC))[1] AS example_record_id,
       (ARRAY_AGG(r.raw             ORDER BY r.created_at DESC))[1]  AS example_payload
FROM ingestion_records r
CROSS JOIN LATERAL jsonb_array_elements(r.rejection_reasons) AS reason(value)
WHERE r.status = 'REJECTED'
GROUP BY r.source, reason.value ->> 'field', reason.value ->> 'code';

COMMENT ON VIEW ingestion_rejections IS
    'What an external feed is getting wrong, grouped by field and reason, with '
    'a sample payload to take back to whoever publishes it.';
