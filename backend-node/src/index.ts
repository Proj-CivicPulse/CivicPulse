import { createServer } from 'node:http';
import { createApp } from './app.ts';
import { env } from './config/env.ts';
import { logger } from './config/logger.ts';
import { pool } from './db/pool.ts';

const SHUTDOWN_TIMEOUT_MS = 10_000;

const app = createApp();
const server = createServer(app);

server.listen(env.PORT, () => {
    logger.info({ port: env.PORT, nodeEnv: env.NODE_ENV }, 'backend-node listening');
});

let shuttingDown = false;

/**
 * Graceful shutdown: stop accepting connections, let in-flight requests
 * drain, close the DB pool, then exit. A force-exit timer guarantees the
 * process dies even if something hangs.
 */
async function shutdown(signal: NodeJS.Signals): Promise<void> {
    if (shuttingDown) return;
    shuttingDown = true;
    logger.info({ signal }, 'shutdown initiated');

    const forceExit = setTimeout(() => {
        logger.error('graceful shutdown timed out — forcing exit');
        process.exit(1);
    }, SHUTDOWN_TIMEOUT_MS);
    forceExit.unref();

    try {
        await new Promise<void>((resolve, reject) => {
            server.close((err) => (err ? reject(err) : resolve()));
        });
        logger.info('http server closed to new connections');

        await pool.end();
        logger.info('postgres pool closed');

        clearTimeout(forceExit);
        process.exit(0);
    } catch (err) {
        logger.error({ err }, 'error during shutdown');
        process.exit(1);
    }
}

for (const signal of ['SIGTERM', 'SIGINT'] as const) {
    process.on(signal, () => {
        void shutdown(signal);
    });
}

// Last-resort safety nets. These should never fire in normal operation;
// if they do, log loudly and (for an uncaught exception) bail out — the
// process state is no longer trustworthy.
process.on('unhandledRejection', (reason) => {
    logger.error({ reason }, 'unhandled promise rejection');
});
process.on('uncaughtException', (err) => {
    logger.fatal({ err }, 'uncaught exception — exiting');
    process.exit(1);
});
