/**
 * Loaded via `node --import` before any test module, so src/config/env.ts sees
 * a complete, valid environment without a real .env. dotenv (run inside env.ts)
 * does not override values already present in process.env, so anything set here
 * wins.
 *
 * Unit tests mock pool.query and fetch — none of these point at a live service.
 */
process.env.NODE_ENV = 'test';
process.env.LOG_LEVEL ??= 'silent';
process.env.DATABASE_URL ??= 'postgres://user:pass@localhost:5432/civicpulse_test';
process.env.FRONTEND_ORIGIN ??= 'http://localhost:5173';
process.env.SPRING_INTERNAL_BASE_URL ??= 'http://localhost:8080';
process.env.INTERNAL_TOKEN ??= 'test-internal-token';
process.env.EMBEDDING_API_KEY ??= 'test-embedding-key';
process.env.EMBEDDING_DIMENSIONS ??= '4';
process.env.MATCH_SIMILARITY_THRESHOLD ??= '0.75';
process.env.PROCESSING_STALE_SECONDS ??= '45';
