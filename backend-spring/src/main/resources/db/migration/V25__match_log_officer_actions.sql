-- Officer merge and unlink (docs/roadmap.md backlog: the manual override for
-- false merges). Both are recorded in incident_match_log like every other
-- membership change, with decision MERGED or UNLINKED and matcher OFFICER,
-- so the log stays the one history of how each complaint reached its
-- incident. An UNLINKED row is also a labelled false merge: the evaluation
-- data the matcher has been missing.
--
-- The new columns say who decided and why. NULL for machine decisions.

ALTER TABLE incident_match_log
    ADD COLUMN actor_user_id BIGINT,
    ADD COLUMN note          VARCHAR(500),
    ADD CONSTRAINT fk_match_log_actor FOREIGN KEY (actor_user_id) REFERENCES users (id);

COMMENT ON COLUMN incident_match_log.actor_user_id IS
    'The officer who merged or unlinked; NULL for a matcher decision.';
COMMENT ON COLUMN incident_match_log.note IS
    'The officer''s reason for a merge or unlink, if they gave one.';
