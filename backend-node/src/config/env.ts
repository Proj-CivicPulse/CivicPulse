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
    // Comma-separated list of allowed browser origins, so one variable covers
    // the production frontend plus any preview deployments. Each entry may
    // contain a "*" wildcard (https://myapp-*.vercel.app) because Vercel mints
    // a new preview URL per commit and they cannot be listed exhaustively.
    //
    // A BARE "*" is rejected: credentials are enabled on this API, and the CORS
    // spec forbids that combination. A host pattern is still an allowlist.
    FRONTEND_ORIGIN: z
        .string()
        .min(1, 'is required')
        .transform((v) =>
            v
                .split(',')
                .map((origin) => origin.trim())
                .filter((origin) => origin.length > 0),
        )
        .refine((origins) => origins.length > 0, {
            message: 'must list at least one origin',
        })
        .refine((origins) => !origins.includes('*'), {
            message:
                'must not contain a bare "*" — credentials are enabled. List origins explicitly, or use a host pattern such as https://myapp-*.vercel.app',
        })
        .refine((origins) => origins.every(isUrl), {
            message: 'every comma-separated entry must be a valid origin URL',
        }),
    LOG_LEVEL: z
        .enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent'])
        .default('info'),
    // Ceiling on the /health database probe. Defaults high enough to absorb
    // a Neon cold start (its compute suspends when idle); lower it for a
    // local database.
    HEALTHCHECK_TIMEOUT_MS: z.coerce.number().int().positive().default(10_000),

    // --- Phase 2: embeddings + incident matching ---------------------------

    // backend-spring, for the /internal/* calls this service makes (complaint
    // context in, match decision out). NOT the browser-facing base URL.
    SPRING_INTERNAL_BASE_URL: z
        .string()
        .min(1, 'is required')
        .refine(isUrl, { message: 'must be a valid URL' })
        .default('http://localhost:8080'),

    // Shared secret for BOTH directions of Spring<->Node internal traffic:
    // sent as X-Internal-Token on calls to Spring, and required on the inbound
    // POST /complaints/:id/process. Blank denies that route — fail closed.
    INTERNAL_TOKEN: z.string().min(1, 'is required'),

    // Gemini embeddings (AI Studio / Generative Language API key — a different
    // key from backend-spring's Google geocoding key).
    EMBEDDING_API_KEY: z.string().min(1, 'is required'),
    EMBEDDING_MODEL: z.string().min(1).default('gemini-embedding-001'),
    // A Gemini "recommended" size and <= 2000 so a pgvector ANN index stays
    // possible later. Must equal the vector(<dim>) column in backend-spring's
    // V6 migration — changing it means re-embedding every complaint.
    EMBEDDING_DIMENSIONS: z.coerce.number().int().positive().max(3072).default(1536),
    EMBEDDING_TASK_TYPE: z
        .enum([
            'SEMANTIC_SIMILARITY',
            'CLUSTERING',
            'RETRIEVAL_DOCUMENT',
            'RETRIEVAL_QUERY',
            'CLASSIFICATION',
        ])
        .default('SEMANTIC_SIMILARITY'),
    EMBEDDING_TIMEOUT_MS: z.coerce.number().int().positive().default(10_000),

    // Cosine-similarity cut-off for joining an existing incident vs. starting a
    // new one. Empirical — tune it in Phase 8; every score is logged regardless.
    MATCH_SIMILARITY_THRESHOLD: z.coerce.number().min(0).max(1).default(0.75),

    // Post-hoc distance check on a semantic match: a candidate incident is
    // accepted only if its most similar member lies within this many metres of
    // the new complaint. 0 disables it. Set from labelled production joins
    // (docs/matching-evaluation.md): every same-problem join was within 202 m,
    // the far wrong joins 843–2108 m apart, and at 500 m the check removed 6 of
    // 22 wrong joins while losing none of 27 right ones.
    MATCH_MAX_SIBLING_DISTANCE_M: z.coerce.number().min(0).default(500),

    // --- Officer Copilot (docs/next-phases-plan.md, Phase A) ---
    // Which model answers. Unset/none: the Copilot is off (503
    // COPILOT_UNAVAILABLE) and everything else runs normally.
    //   ollama    — local, free; needs Ollama running at OLLAMA_BASE_URL
    //   gemini    — Google; GEMINI_API_KEY or the embedding key; billed per call
    //   anthropic — Claude; needs ANTHROPIC_API_KEY; billed per call
    COPILOT_PROVIDER: z.enum(['none', 'ollama', 'gemini', 'anthropic']).default('none'),
    OLLAMA_BASE_URL: z.string().url().default('http://localhost:11434'),
    // Small enough for a laptop, and good at following a JSON schema.
    COPILOT_OLLAMA_MODEL: z.string().min(1).default('qwen2.5:7b'),
    COPILOT_GEMINI_MODEL: z.string().min(1).default('gemini-2.5-flash'),
    GEMINI_API_KEY: z
        .string()
        .optional()
        .transform((v) => (v && v.trim().length > 0 ? v.trim() : undefined)),
    // An empty value counts as unset, so a blank line in .env cannot
    // masquerade as a key.
    ANTHROPIC_API_KEY: z
        .string()
        .optional()
        .transform((v) => (v && v.trim().length > 0 ? v.trim() : undefined)),
    COPILOT_MODEL: z.string().min(1).default('claude-opus-5-5'),
    // Grounded Q&A over a small packet: medium depth is the measured-cost
    // starting point; raise only if the golden-question eval shows headroom.
    COPILOT_EFFORT: z.enum(['low', 'medium', 'high', 'xhigh', 'max']).default('medium'),
    COPILOT_MAX_TOKENS: z.coerce.number().int().positive().max(16000).default(8000),
    // Must stay below backend-spring's copilot read timeout.
    COPILOT_TIMEOUT_MS: z.coerce.number().int().positive().default(45_000),

    // A PROCESSING claim older than this is considered abandoned (Node crashed
    // mid-run) and may be re-taken. MUST match backend-spring's
    // app.matching.processing-stale-seconds — Node's CAS is the authority, the
    // Spring value only pre-filters reconcile candidates, so drift just costs a
    // wasted `skipped` round-trip. Keep it above EMBEDDING_TIMEOUT_MS + slack.
    PROCESSING_STALE_SECONDS: z.coerce.number().int().positive().default(45),
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
