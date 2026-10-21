# CivicPulse database: PostgreSQL 16 + pgvector + PostGIS.
#
# The schema needs both extensions — V1 creates `vector` (complaint embeddings
# for matching) and V13 creates `postgis` (real ward boundaries) — and no stock
# image ships both. This starts from the official pgvector image (Debian
# bookworm, with the PostgreSQL apt repository already configured) and adds
# PostGIS from that repository.
#
# Not the other way round: the postgis/postgis images are still built on
# bullseye, whose PostgreSQL apt repository has been retired (404), so
# pgvector cannot be installed on top of them.
#
# Used for the Railway database service (docs/railway-database.md) and for a
# local throwaway database to run the test suite against (TEST_DB_URL), so
# tests never touch production again.
FROM pgvector/pgvector:pg16

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        postgresql-16-postgis-3 \
        postgresql-16-postgis-3-scripts \
    && rm -rf /var/lib/apt/lists/*
