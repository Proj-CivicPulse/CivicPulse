-- Separate WHEN A PROBLEM WAS REPORTED from WHEN WE CREATED THE ROW.
--
-- Until now these shared one column. For a complaint typed into the website
-- that is harmless — they are the same instant. For one imported from an
-- external feed they are not, and V15 resolved the conflict by OVERWRITING
-- created_at with the upstream time. That made one column mean two different
-- things depending on how the row arrived, and it had a concrete cost:
-- ComplaintRepository.findReconcileCandidates ordered by created_at, so a
-- backdated import would have jumped the whole reconcile queue ahead of
-- complaints residents filed that morning.
--
-- Now:
--   created_at  — when CivicPulse created this row. Never rewritten. Ordering
--                 by it, or by id, is arrival order.
--   reported_at — when the problem was actually reported. Equal to created_at
--                 for a website submission; the upstream timestamp for an
--                 imported one. This is what the priority formula's
--                 time-dependent terms read.
--
-- Doing this NOW is what makes it cheap: no external feed is configured and
-- `ingestion_records` is empty, so there is no row anywhere whose created_at
-- has already been overwritten. Every existing complaint genuinely was reported
-- when it was created, which makes the backfill exact rather than a guess.

ALTER TABLE complaints ADD COLUMN reported_at TIMESTAMP(6);

-- Exact, not approximate: every row predating this migration was created by the
-- public submit path, where the two instants are the same by definition.
UPDATE complaints SET reported_at = created_at WHERE reported_at IS NULL;

-- NOT NULL, because "we do not know when this was reported" is not a state any
-- write path can produce — the ingestion gate defaults a missing upstream
-- timestamp to the ingestion moment rather than leaving it absent, and the
-- public path always knows.
ALTER TABLE complaints ALTER COLUMN reported_at SET NOT NULL;

-- The growth term counts members reported in the last 24 hours, and the
-- stale-incident analytics in Phase 5 will want the same axis.
CREATE INDEX idx_complaints_reported_at ON complaints (reported_at);
