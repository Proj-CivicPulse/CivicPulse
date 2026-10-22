-- Life-safety flag on incidents (docs/priority-calibration-proxy.md, "What the
-- evidence does support"). A problem that can kill someone — an open drain or
-- manhole, exposed wiring, sewage overflow, a collapsed road, a fallen tree or
-- pole — is flagged on the INCIDENT, because the hazard belongs to the
-- problem, not to its category.
--
-- The flag feeds decision support (escalate) and the "needs attention" list,
-- never the priority score: the score's weights stay as calibrated.
--
-- Two sources, and the officer's word is final:
--   REPORT_TEXT  set by LifeSafetyDetector from the members' text on every
--                recompute. A suggestion for an officer to confirm or clear.
--   OFFICER      set through PATCH /incidents/{id}. Sticky: later reports no
--                longer change it, so a cleared false alarm stays cleared.
-- NULL source means nothing has ever flagged it.

ALTER TABLE incidents
    ADD COLUMN life_safety        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN life_safety_source VARCHAR(16),
    ADD COLUMN life_safety_reason VARCHAR(255),
    ADD CONSTRAINT ck_incidents_life_safety_source
        CHECK (life_safety_source IN ('REPORT_TEXT', 'OFFICER')),
    -- A flag always says who raised it.
    ADD CONSTRAINT ck_incidents_life_safety_has_source
        CHECK (NOT life_safety OR life_safety_source IS NOT NULL);

-- Existing incidents are flagged by the next priority sweep, which runs
-- recompute (and so the detector) on every active incident.
