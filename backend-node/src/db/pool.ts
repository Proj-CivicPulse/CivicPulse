import { Pool } from 'pg';
import { env } from '../config/env.ts';
import { logger } from '../config/logger.ts';

// Single shared connection pool for the whole process.
//
// TLS comes from `sslmode=require` in DATABASE_URL — node-postgres parses it
// off the connection string, so there is no `ssl` option to set here. Neon
// refuses plaintext connections, so a URL missing that parameter fails to
// connect rather than silently downgrading.
//
// pgvector needs no special client config: when Phase 2 adds vector columns,
// queries just cast to/from `vector` in SQL.
export const pool = new Pool({
    connectionString: env.DATABASE_URL,
    max: 10,
    // Neon suspends its compute when idle, so releasing connections quickly
    // lets it actually sleep instead of being held awake for nothing.
    idleTimeoutMillis: 30_000,
    // Longer than a local socket would ever need: waking a suspended Neon
    // compute takes a second or two, and that shows up as connection time.
    connectionTimeoutMillis: 15_000,
    // allowExitOnIdle stays at its default (false): the pool is closed
    // explicitly by the graceful-shutdown handler in src/index.ts.
});

// Errors on idle clients (DB restarted, network blip) surface here rather
// than on a specific query. Log and let pg replace the client.
pool.on('error', (err) => {
    logger.error({ err }, 'unexpected error on idle postgres client');
});

// Upper bound on the health probe. Guards the case where the connection is
// up at the TCP level but the server never answers (frozen DB, network
// black hole) — pool.query() would otherwise hang with no client-side
// timeout. Scoped to the health check so it doesn't constrain real queries.
//
// Sized for a Neon cold start, not a local socket: too tight and an idle
// database reports itself down on the first request after a quiet period.
const HEALTHCHECK_TIMEOUT_MS = env.HEALTHCHECK_TIMEOUT_MS;

/**
 * Real dependency check for the health endpoint. Runs a trivial
 * parameter-free query; returns false (never throws, never hangs) if the
 * DB is unreachable or unresponsive so the caller can turn that into a 503.
 *
 * NOTE for Phase 2+ query code that will live alongside this: every query
 * MUST use parameterized placeholders — pool.query('... WHERE id = $1', [id]).
 * Never build SQL by string concatenation or template interpolation.
 */
export async function checkDatabase(): Promise<boolean> {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
        const query = pool.query<{ ok: number }>('SELECT 1 AS ok');
        const timeout = new Promise<never>((_resolve, reject) => {
            timer = setTimeout(
                () => reject(new Error('health check query timed out')),
                HEALTHCHECK_TIMEOUT_MS,
            );
        });
        const result = await Promise.race([query, timeout]);
        return result.rows[0]?.ok === 1;
    } catch (err) {
        logger.error({ err }, 'database health check failed');
        return false;
    } finally {
        if (timer) clearTimeout(timer);
    }
}
