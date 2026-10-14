-- When an incident's problem was first and last reported, as columns.
--
-- The Explorer needs both on every row of the incident list -- "first
-- reported" for age, "last reported" for recency sorting and for the default
-- tie-break after priority (docs/api-contract.md, E1/E2). They are
-- min/max(complaints.reported_at) over the members.
--
-- NOT incidents.created_at. That is when the GROUPING was made, which for an
-- imported member can be months after the problem was reported, and which the
-- officer console's "Age" column was reading until this change.
--
-- Stored rather than aggregated per request, the same way complaint_count and
-- the centroid already are: IncidentAttachmentService.recompute re-derives all
-- of them from the member list on every membership change, and
-- PriorityRefreshJob runs recompute on every open incident, so the columns
-- cannot drift from their members for longer than one sweep. Stored also means
-- sortable through an index, where a per-request aggregate would not be.

ALTER TABLE incidents ADD COLUMN first_reported_at TIMESTAMP(6);
ALTER TABLE incidents ADD COLUMN last_reported_at  TIMESTAMP(6);

-- Backfill from the members. An incident with no members (not expected --
-- an emptied incident is deleted) is left NULL rather than given a guess.
UPDATE incidents i
SET first_reported_at = span.first_reported,
    last_reported_at  = span.last_reported
FROM (
    SELECT incident_id,
           MIN(reported_at) AS first_reported,
           MAX(reported_at) AS last_reported
    FROM complaints
    WHERE incident_id IS NOT NULL
    GROUP BY incident_id
) span
WHERE span.incident_id = i.id;

-- The Explorer's recency sort.
CREATE INDEX idx_incidents_last_reported_at ON incidents (last_reported_at);

COMMENT ON COLUMN incidents.first_reported_at IS
    'min(complaints.reported_at) over members. Maintained by recompute; never '
    'read incidents.created_at for "first reported".';
COMMENT ON COLUMN incidents.last_reported_at IS
    'max(complaints.reported_at) over members. Maintained by recompute.';
