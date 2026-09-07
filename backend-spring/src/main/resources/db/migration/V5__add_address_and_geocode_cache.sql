-- Human-readable addresses for coordinates.
--
-- "12.926000, 77.594000" tells a resident nothing about their own report and
-- tells an officer nothing about where to send a crew. An address is the one
-- piece of context both sides actually need.
--
-- WHY A CACHE TABLE
-- Reverse geocoding is a paid, rate-limited, third-party call. The addresses it
-- returns are effectively immutable: the street outside a pothole does not get
-- renamed. So the answer is stored twice, deliberately —
--
--   1. denormalised onto complaints/incidents, so reads never touch the cache
--      or the network at all; and
--   2. keyed by rounded coordinates here, so the FIRST report at a location
--      pays for the lookup and every later report at the same spot is free.
--
-- Reports cluster hard (a pothole gets reported from the same 50 m of road over
-- and over), so the cache is what keeps call volume proportional to distinct
-- LOCATIONS rather than to complaint count.

ALTER TABLE complaints ADD COLUMN address VARCHAR(512);
ALTER TABLE incidents  ADD COLUMN address VARCHAR(512);

CREATE TABLE geocode_cache (
    -- Coordinates rounded to 4 decimal places, ~11 m. Finer than street-address
    -- granularity, so two points sharing a key genuinely share an address —
    -- but coarse enough that a cluster of reports collapses to a few lookups.
    -- Stored as the rounded NUMERIC rather than a formatted string so the key
    -- cannot drift with locale or formatting changes.
    lat_key    NUMERIC(9, 4) NOT NULL,
    lon_key    NUMERIC(9, 4) NOT NULL,
    address    VARCHAR(512),
    -- A negative result is cached too: if Google has no address for a point in
    -- a field, asking again next week will not produce one either, and paying
    -- to rediscover that is the worst kind of spend.
    resolved   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (lat_key, lon_key)
);
