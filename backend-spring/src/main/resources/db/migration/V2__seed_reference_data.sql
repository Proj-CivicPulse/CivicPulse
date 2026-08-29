-- Minimal reference data so complaints have something to reference before
-- real city data is loaded. Wards and departments are public reference
-- values, not secrets — safe to keep in version control.
--
-- No users are seeded here on purpose: a committed password hash is a
-- credential in the repo. To create the first officer, register normally
-- via POST /auth/register (which always creates a CITIZEN) and then promote
-- the account — see "Creating an officer" in backend-spring/README.md.

INSERT INTO wards (name, zone, created_at, updated_at) VALUES
    ('Ward 1',  'North', NOW(), NOW()),
    ('Ward 2',  'North', NOW(), NOW()),
    ('Ward 17', 'South', NOW(), NOW()),
    ('Ward 23', 'East',  NOW(), NOW());

INSERT INTO departments (name, created_at, updated_at) VALUES
    ('Public Works',   NOW(), NOW()),
    ('Sanitation',     NOW(), NOW()),
    ('Water Supply',   NOW(), NOW()),
    ('Street Lighting', NOW(), NOW());
