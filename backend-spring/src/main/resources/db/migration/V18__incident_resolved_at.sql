-- When an incident was resolved, as a fact rather than an inference.
--
-- The post-midsem roadmap asks the Explorer for resolution metrics -- average
-- time to resolve, resolved-in-period counts, ward and category comparisons.
-- None of that is computable today. `status` says an incident IS resolved and
-- nothing records WHEN it became so.
--
-- The tempting substitute is updated_at, and it is worse than having nothing:
-- PriorityRefreshJob touches every open incident every five minutes, so a
-- metric built on updated_at would measure the sweep rather than the work.
-- Deriving it would silently produce plausible, wrong numbers -- the failure
-- mode the reported_at split (V16) existed to end.
--
-- Scope: INCIDENTS ONLY, deliberately. Complaints carry their own status and
-- could take the same column, but then "how long did this take?" would have two
-- answers that disagree whenever a member complaint is resolved while its
-- incident is still open. The incident is the operational entity -- it is what
-- gets assigned, prioritised and closed -- so it is where resolution time
-- lives. Revisit if officers turn out to work complaint-by-complaint in
-- practice; the evidence for that would be a sustained population of RESOLVED
-- complaints inside OPEN incidents.

ALTER TABLE incidents ADD COLUMN resolved_at TIMESTAMP(6);

-- No backfill, and nothing to backfill: every incident in every environment is
-- currently OPEN (verified against production before writing this -- 31 of 31).
-- Had there been resolved rows, they would still have been left NULL rather
-- than given a guessed timestamp, and the metric documented as starting here.

-- Partial: the rows without a resolution time are the overwhelming majority and
-- no query wants them. Resolution analytics all read "resolved incidents in a
-- window", which is exactly this index.
CREATE INDEX idx_incidents_resolved_at ON incidents (resolved_at)
    WHERE resolved_at IS NOT NULL;

COMMENT ON COLUMN incidents.resolved_at IS
    'When status last entered RESOLVED. NULL whenever status is not RESOLVED -- '
    'reopening an incident clears it, because a resolution time on an open '
    'incident would be counted by every "resolved in period" query.';
