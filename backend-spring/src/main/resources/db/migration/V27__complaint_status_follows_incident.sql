-- An officer's status change on an incident now carries down to every report
-- in it (IncidentService.applyStatus), so the resident who filed one sees it on
-- My reports. Before that, only the incident row moved and every report stayed
-- 'OPEN' however the incident ended.
--
-- Catch up the incidents an officer already moved. Every incident is created
-- OPEN and only an officer moves it, so a non-OPEN incident is one an officer
-- decided on. OPEN incidents are left alone: an imported report keeps the
-- upstream status it arrived with until an officer acts on its incident.

UPDATE complaints c
SET status = i.status,
    updated_at = now()
FROM incidents i
WHERE c.incident_id = i.id
  AND i.status <> 'OPEN'
  AND c.status <> i.status;
