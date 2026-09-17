-- Acting on V11's quarantine report.
--
-- V11 deliberately left unrecognised categories alone rather than sweeping them
-- into 'other', and told you how to find them:
--
--   SELECT category, COUNT(*) FROM complaints
--   WHERE category NOT IN (SELECT code FROM categories) GROUP BY category;
--
-- Run against real data, that returned two values — 'noise' (4 complaints,
-- 2 incidents) and 'footpath' (4 complaints, 1 incident). Neither is a typo or
-- a bad import. Both are ordinary civic complaints the seeded vocabulary simply
-- did not cover: construction noise before dawn, and a lifted footpath slab an
-- elderly resident tripped on.
--
-- That is the registry working as intended. The quarantine did not discard
-- them, it surfaced them for a person to decide on, and the decision is that
-- they are real categories rather than 'other'.
--
-- Why not fold them into what already exists:
--   - 'footpath' is NOT 'pothole'. A pedestrian hazard and a carriageway defect
--     go to different works teams, and merging them would put a tripping hazard
--     into a queue ranked by traffic disruption.
--   - 'noise' has no relative in the list at all. Folding it into 'other' would
--     bury a distinct, actionable complaint type in the bucket reserved for
--     things with no home.

INSERT INTO categories (code, name, display_name, sort_order, created_at, updated_at) VALUES
    ('footpath', 'Footpath',        'Damaged or blocked footpath', 60, NOW(), NOW()),
    ('noise',    'Noise nuisance',  'Excessive noise',             70, NOW(), NOW());

-- Aliases, in the same already-normalised form V11 established: lowercased,
-- runs of non-alphanumerics collapsed to one space, trimmed.
INSERT INTO category_aliases (category_id, alias, source, created_at)
SELECT c.id, a.alias, 'internal', NOW()
FROM categories c
JOIN (VALUES
    ('footpath', 'footpath'),
    ('footpath', 'footpaths'),
    ('footpath', 'sidewalk'),
    ('footpath', 'pavement'),
    ('footpath', 'broken footpath'),
    ('footpath', 'damaged footpath'),
    ('footpath', 'blocked footpath'),
    ('footpath', 'footpath encroachment'),
    ('noise',    'noise'),
    ('noise',    'noise pollution'),
    ('noise',    'noise nuisance'),
    ('noise',    'loud noise'),
    ('noise',    'loudspeaker'),
    ('noise',    'construction noise')
) AS a(code, alias) ON a.code = c.code;

-- No backfill: the stored values already read 'noise' and 'footpath', which are
-- now the canonical codes. The rows were never wrong — the registry was
-- incomplete. source_category stays NULL on them for the same reason it does on
-- every row that was already canonical.

-- Post-condition: the quarantine report must now be empty. If it is not, a
-- THIRD unknown category appeared after V11 was written, and the right response
-- is another reviewed decision like this one — not a silent catch-all.
DO $$
DECLARE
    leftover_count INTEGER;
    leftover_list  TEXT;
BEGIN
    SELECT COUNT(*), string_agg(DISTINCT category, ', ')
      INTO leftover_count, leftover_list
      FROM complaints WHERE category NOT IN (SELECT code FROM categories);

    IF leftover_count > 0 THEN
        RAISE WARNING 'complaint categories still outside the registry (% rows): %',
            leftover_count, leftover_list;
    END IF;
END $$;
