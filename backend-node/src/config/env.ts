import { z } from 'zod';
import dotenv from 'dotenv';

// Load .env into process.env before validation. In production the platform
// injects real env vars and .env simply won't exist — that's fine.
dotenv.config();

/** Validates a string parses as a URL without pulling in a zod version-specific format helper. */
const isUrl = (value: string): boolean => {
    try {
        new URL(value);
        return true;
    } catch {
        return false;
    }
};

const EnvSchema = z.object({
    NODE_ENV: z.enum(['development', 'production', 'test']).default('development'),
    PORT: z.coerce.number().int().positive().max(65535).default(3001),
    DATABASE_URL: z
        .string()
        .min(1, 'is required')
        .refine((v) => v.startsWith('postgres://') || v.startsWith('postgresql://'), {
            message: 'must be a postgres:// or postgresql:// connection string',
        }),
    FRONTEND_ORIGIN: z.string().min(1, 'is required').refine(isUrl, { message: 'must be a valid URL' }),
    LOG_LEVEL: z
        .enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent'])
        .default('info'),
    // Ceiling on the /health database probe. Defaults high enough to absorb
    // a Neon cold start (its compute suspends when idle); lower it for a
    // local database.
    HEALTHCHECK_TIMEOUT_MS: z.coerce.number().int().positive().default(10_000),
});

export type Env = z.infer<typeof EnvSchema>;

function loadEnv(): Env {
    const parsed = EnvSchema.safeParse(process.env);

    if (!parsed.success) {
        // The logger isn't available yet (it depends on this module), and a
        // malformed DATABASE_URL may contain a password — so report only the
        // variable name and the rule it broke, never the value. Deliberate,
        // single exception to the "no console" rule: startup config failure,
        // written to stderr, before the process can usefully run.
        const details = parsed.error.issues
            .map((issue) => `  - ${issue.path.join('.') || '(env)'}: ${issue.message}`)
            .join('\n');
        // oxlint-disable-next-line no-console
        console.error(`Invalid environment configuration:\n${details}\n\nSee .env.example for the expected shape.`);
        process.exit(1);
    }

    return parsed.data;
}

export const env: Env = loadEnv();

export const isProduction = env.NODE_ENV === 'production';
export const isDevelopment = env.NODE_ENV === 'development';
