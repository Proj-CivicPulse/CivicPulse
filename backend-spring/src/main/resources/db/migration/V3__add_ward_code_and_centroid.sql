-- Ward identity and geography.
--
-- `code` is the stable ward number that appears in a complaint reference
-- number (CP-2026-W17-00412). It is parsed once, here, from the seeded display
-- name rather than read from `name` at generation time: `name` is a mutable
-- label an officer may rename, and a reference number printed on a resident's
-- receipt must never change underneath them. Real municipal ward codes also
-- get a home here when city data is imported.
--
-- Using the primary key instead would have been simpler, but the receipt would
-- read "W3" while the whole UI says "Ward 17" — which defeats the point of a
-- human-readable reference.

ALTER TABLE wards
    ADD COLUMN code      VARCHAR(16),
    ADD COLUMN latitude  DOUBLE PRECISION,
    ADD COLUMN longitude DOUBLE PRECISION;

-- 'Ward 17' -> '17'. Falls back to the id for any name without digits, so the
-- NOT NULL below holds for imported names like 'Jayanagar'.
UPDATE wards
SET code = COALESCE(NULLIF(substring(name FROM '(\d+)'), ''), id::text)
WHERE code IS NULL;

ALTER TABLE wards ALTER COLUMN code SET NOT NULL;
ALTER TABLE wards ADD CONSTRAINT uq_wards_code UNIQUE (code);

-- Centroids stay NULLABLE on purpose: a ward imported from city data may not
-- carry coordinates, and refusing to store it would be worse than storing it
-- without a centroid. CentroidWardResolver filters null-centroid wards out of
-- its candidate set instead.
--
-- These four are plausible Bengaluru coordinates consistent with the zones
-- seeded in V2. They are placeholders for real boundary data, not survey data.
UPDATE wards SET latitude = 13.0358, longitude = 77.5970 WHERE name = 'Ward 1';   -- North
UPDATE wards SET latitude = 13.1007, longitude = 77.5963 WHERE name = 'Ward 2';   -- North
UPDATE wards SET latitude = 12.9250, longitude = 77.5938 WHERE name = 'Ward 17';  -- South
UPDATE wards SET latitude = 12.9784, longitude = 77.6408 WHERE name = 'Ward 23';  -- East
