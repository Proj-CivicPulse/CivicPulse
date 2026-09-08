-- incident_match_log.decision could not answer "did this decision start a new
-- incident?" for reconcile rows.
--
-- decision is MATCHED (joined an existing incident), CREATED (started a new
-- one), or RECONCILED (moved off the incident a naive fallback had chosen).
-- RECONCILED wins over the other two when a complaint moves — so a reconcile
-- that moved a complaint into a BRAND NEW incident and one that moved it into
-- an EXISTING incident both record RECONCILED, and nothing distinguishes them.
--
-- That is precisely the join-vs-split distinction Phase 8 measures precision and
-- recall on, and it is the majority of the rows. Recording it explicitly beats
-- deriving it by scanning earlier rows for the incident id, which is both
-- fragile and wrong once an incident is deleted.

ALTER TABLE incident_match_log ADD COLUMN created BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill what is unambiguous from the existing rows; RECONCILED rows written
-- before this migration stay FALSE, which is merely unknown rather than wrong,
-- and they are excluded from the Phase 8 join/split analysis by created_at.
UPDATE incident_match_log SET created = TRUE WHERE decision = 'CREATED';

COMMENT ON COLUMN incident_match_log.created IS
    'True when this decision started a new incident (as opposed to joining an existing one). Meaningful for every decision value, including RECONCILED.';
