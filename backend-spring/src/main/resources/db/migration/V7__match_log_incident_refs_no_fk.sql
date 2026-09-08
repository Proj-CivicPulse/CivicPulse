-- incident_match_log is an append-only analytics log for the Phase 8 evaluation.
-- Its incident references are historical, not live pointers: the reconcile job
-- deletes an incident once its last complaint has been moved off it, so
-- chosen_incident_id and previous_incident_id can both legitimately point at a
-- since-removed row. A foreign key would either block that cleanup or, with
-- ON DELETE SET NULL, erase the exact naive-vs-semantic disagreement the log
-- exists to record.
--
-- complaint_id and top_sibling_complaint_id keep their FKs — complaints are
-- never deleted.

ALTER TABLE incident_match_log DROP CONSTRAINT fk_match_log_chosen_incident;
ALTER TABLE incident_match_log DROP CONSTRAINT fk_match_log_previous_incident;
