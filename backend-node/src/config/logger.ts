import pino from 'pino';
import { env, isDevelopment } from './env.ts';

// pino is a non-blocking structured JSON logger — used everywhere instead
// of console.* so logging never stalls the event loop under load.
export const logger = pino({
    level: env.LOG_LEVEL,
    // Pretty-print in dev only; production emits raw JSON lines for
    // log shippers to parse.
    ...(isDevelopment
        ? {
              transport: {
                  target: 'pino-pretty',
                  options: { colorize: true, translateTime: 'SYS:standard', ignore: 'pid,hostname' },
              },
          }
        : {}),
    // Defense in depth: strip anything that could carry a secret or PII if
    // it ever ends up on a log object. Secrets should never reach here in
    // the first place.
    redact: {
        paths: [
            'req.headers.authorization',
            'req.headers.cookie',
            'DATABASE_URL',
            'connectionString',
            '*.DATABASE_URL',
            '*.password',
        ],
        remove: true,
    },
});
