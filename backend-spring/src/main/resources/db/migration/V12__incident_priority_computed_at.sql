-- When an incident's priority was last derived.
--
-- The priority formula has a time-dependent term (age, saturating at 14 days),
-- so a STORED score drifts away from the correct one with no write at all. Up
-- to now recompute happened only on a membership change — attach, reconcile,
-- unlink — which meant an incident that stopped attracting reports also stopped
-- ageing, and the fairness term the age weight exists to provide silently
-- stopped working. PriorityService says as much in its own KNOWN STALENESS note.
--
-- PriorityRefreshJob closes that by sweeping open incidents on a schedule. It
-- needs to know which ones are already current, and `updated_at` cannot answer
-- that: any write bumps it — a status change, a department assignment — so
-- using it would mark an incident fresh that was never rescored.
--
-- Exposed on the incident DTO too. An officer looking at a ranked queue is
-- entitled to know how old the ranking is, and a dashboard that cannot say is
-- how a stale band goes unnoticed.
ALTER TABLE incidents ADD COLUMN priority_computed_at TIMESTAMP(6);

-- Existing rows: the score on them was computed at some unknown past point, so
-- NULL is the honest value and the sweep treats NULL as "oldest", picking them
-- up first. Backfilling updated_at here would assert a freshness that is not
-- true.

-- The sweep orders by this column over the active statuses.
CREATE INDEX idx_incidents_priority_computed_at
    ON incidents (status, priority_computed_at);
