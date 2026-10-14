-- Which department owns each category, as data rather than as SQL comments.
--
-- The routing knowledge already existed. V11 keeps 'sewage' out of 'water'
-- because "a water-supply failure and a sewage overflow belong to different
-- departments"; V14 keeps 'footpath' out of 'pothole' because they go to
-- "different works teams". But it lived only in comments, so nothing could ask
-- "whose queue is this incident in?" -- and no complaint or incident in
-- production has a department (department_id is set only by an officer's PATCH,
-- and none has been).
--
-- This is a DEFAULT, not an assignment. incidents.department_id stays the
-- officer's decision and is not touched here; an incident's effective owner is
--   COALESCE(incidents.department_id, categories.default_department_id).
-- Auto-filling department_id on new incidents is a separate decision.
--
-- Provisional (decided 2026-10-13 "for now"): the four V2 departments are
-- seed data, not BBMP's real structure, and the mapping will be revisited when
-- real departments are known. docs/category-dataset-audit.md records why each
-- row is what it is.

-- Drainage needs an owner that is not Water Supply -- V11's whole argument --
-- and none of the four seeded departments is it.
INSERT INTO departments (name, created_at, updated_at)
SELECT 'Storm Water Drains', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM departments WHERE name = 'Storm Water Drains');

-- Nullable on purpose: 'noise' is enforced by the police and the pollution
-- control board, not a municipal works department, and 'other' has no owner by
-- definition. A NULL says "triage by hand"; inventing an owner would route
-- those complaints to a queue that cannot act on them.
ALTER TABLE categories ADD COLUMN default_department_id BIGINT;
ALTER TABLE categories ADD CONSTRAINT fk_categories_default_department
    FOREIGN KEY (default_department_id) REFERENCES departments (id);

UPDATE categories c
SET default_department_id = d.id,
    updated_at            = NOW()
FROM (VALUES
    ('pothole',     'Public Works'),
    -- Same department as pothole, different team (V14). The split that
    -- matters for matching is the category, which stays separate.
    ('footpath',    'Public Works'),
    ('streetlight', 'Street Lighting'),
    ('garbage',     'Sanitation'),
    ('water',       'Water Supply'),
    ('drainage',    'Storm Water Drains')
) AS m(code, department)
JOIN departments d ON d.name = m.department
WHERE c.code = m.code;

-- Post-condition: exactly the six mapped codes have an owner. A department
-- renamed or missing would leave a category silently unowned, so fail loudly.
DO $$
DECLARE
    mapped INTEGER;
BEGIN
    SELECT COUNT(*) INTO mapped FROM categories
    WHERE code IN ('pothole', 'footpath', 'streetlight', 'garbage', 'water', 'drainage')
      AND default_department_id IS NOT NULL;

    IF mapped <> 6 THEN
        RAISE EXCEPTION 'expected 6 categories with a default department, found %', mapped;
    END IF;
END $$;

COMMENT ON COLUMN categories.default_department_id IS
    'Department that owns this category by default. NULL = no owning department, '
    'triage by hand. An incident''s own department_id, when set, overrides it.';
