/**
 * The model behind the Copilot, chosen by COPILOT_PROVIDER.
 *
 * Swappable on purpose: the safety of an answer does not come from the model.
 * backend-spring builds the grounding packet and drops every citation it did
 * not put there (CopilotService.checked), whichever model answered — so a
 * smaller, free model is acceptable, and the golden-question eval can compare
 * providers on the same questions.
 *
 *   ollama     a local model (free, private; laptops and demos, not Railway)
 *   gemini     Google's API, using the project's existing Google key
 *   anthropic  Claude, via the official SDK
 */

import Anthropic from '@anthropic-ai/sdk';
import { betaZodOutputFormat } from '@anthropic-ai/sdk/helpers/beta/zod';
import { z } from 'zod';
import { env } from '../config/env.ts';
import { HttpError } from '../middleware/errorHandler.ts';

/** The answer shape every provider must produce. Citations are packet ids. */
export const CopilotOutput = z.object({
    answerable: z.boolean().describe('false when the grounding data cannot answer the question'),
    answer: z.string().describe('The answer for the officer, plain text, at most about 150 words'),
    citations: z.array(z.string()).describe('Packet ids that support the answer, e.g. "incident:45"'),
});
export type CopilotOutput = z.infer<typeof CopilotOutput>;

export interface ProviderResult {
    /** null when the model returned nothing readable. */
    output: CopilotOutput | null;
    /** The model declined (safety). */
    refused: boolean;
    /** The answer was cut off by the output limit. */
    truncated: boolean;
    model: string;
}

export interface CopilotProvider {
    readonly name: string;
    answer(system: string, userText: string): Promise<ProviderResult>;
}

const unavailable = (why: string) => new HttpError(503, 'COPILOT_UNAVAILABLE', why);
const busy = () => new HttpError(503, 'COPILOT_BUSY', 'The Copilot is busy — try again in a minute');
const upstream = (why: string) => new HttpError(502, 'COPILOT_UPSTREAM_ERROR', why);

/** Parses and validates a model's JSON text; null if it is not the agreed shape. */
export function parseOutput(text: string | undefined | null): CopilotOutput | null {
    if (!text) return null;
    try {
        const result = CopilotOutput.safeParse(JSON.parse(text));
        return result.success ? result.data : null;
    } catch {
        return null;
    }
}

// ── Anthropic (Claude) ────────────────────────────────────────────────────

export function anthropicProvider(client?: Anthropic): CopilotProvider {
    let anthropic = client ?? null;
    return {
        name: 'anthropic',
        async answer(system, userText) {
            if (!anthropic) {
                if (!env.ANTHROPIC_API_KEY) throw unavailable('The Copilot is not configured on this server');
                anthropic = new Anthropic({ apiKey: env.ANTHROPIC_API_KEY, timeout: env.COPILOT_TIMEOUT_MS, maxRetries: 1 });
            }
            try {
                const response = await anthropic.beta.messages.parse({
                    model: env.COPILOT_MODEL,
                    max_tokens: env.COPILOT_MAX_TOKENS,
                    // A declined request re-runs server-side on the model Anthropic
                    // recommends for that refusal category, inside this one call.
                    betas: ['server-side-fallback-2026-07-01'],
                    fallbacks: 'default',
                    output_config: { effort: env.COPILOT_EFFORT, format: betaZodOutputFormat(CopilotOutput) },
                    system,
                    messages: [{ role: 'user', content: userText }],
                });
                return {
                    output: response.stop_reason === 'refusal' ? null : response.parsed_output,
                    refused: response.stop_reason === 'refusal',
                    truncated: response.stop_reason === 'max_tokens',
                    model: response.model,
                };
            } catch (err) {
                // Most specific first: a bad key (our misconfiguration) must read
                // differently from a provider outage.
                if (err instanceof HttpError) throw err;
                if (err instanceof Anthropic.AuthenticationError || err instanceof Anthropic.PermissionDeniedError) {
                    throw unavailable('The Copilot is not configured correctly on this server');
                }
                if (err instanceof Anthropic.RateLimitError) throw busy();
                if (err instanceof Anthropic.APIError) throw upstream('The Copilot provider failed');
                throw upstream('The Copilot provider could not be reached');
            }
        },
    };
}

// ── Ollama (local) ────────────────────────────────────────────────────────

/**
 * A model served by Ollama (https://ollama.com) — `ollama pull <model>`, then
 * set COPILOT_PROVIDER=ollama. Structured output via Ollama's `format`, which
 * takes a JSON schema. Temperature 0: an officer asking twice should get the
 * same answer.
 */
export function ollamaProvider(fetchImpl: typeof fetch = fetch): CopilotProvider {
    return {
        name: 'ollama',
        async answer(system, userText) {
            const base = env.OLLAMA_BASE_URL.replace(/\/+$/, '');
            let response: Response;
            try {
                response = await fetchImpl(`${base}/api/chat`, {
                    method: 'POST',
                    signal: AbortSignal.timeout(env.COPILOT_TIMEOUT_MS),
                    headers: { 'content-type': 'application/json' },
                    body: JSON.stringify({
                        model: env.COPILOT_OLLAMA_MODEL,
                        stream: false,
                        format: z.toJSONSchema(CopilotOutput),
                        options: { temperature: 0, num_predict: env.COPILOT_MAX_TOKENS },
                        messages: [
                            { role: 'system', content: system },
                            { role: 'user', content: userText },
                        ],
                    }),
                });
            } catch {
                throw unavailable(`The local model is not reachable at ${base} — is Ollama running?`);
            }
            if (response.status === 404) {
                throw unavailable(`The local model "${env.COPILOT_OLLAMA_MODEL}" is not installed — run: ollama pull ${env.COPILOT_OLLAMA_MODEL}`);
            }
            if (!response.ok) throw upstream(`The local model failed (${response.status})`);
            const body = (await response.json()) as { message?: { content?: string }; done_reason?: string; model?: string };
            return {
                output: parseOutput(body.message?.content),
                refused: false,
                truncated: body.done_reason === 'length',
                model: body.model ?? env.COPILOT_OLLAMA_MODEL,
            };
        },
    };
}

// ── Gemini (Google) ───────────────────────────────────────────────────────

/** Gemini's response schema is an OpenAPI subset, so it is written out rather than generated. */
const GEMINI_SCHEMA = {
    type: 'OBJECT',
    properties: {
        answerable: { type: 'BOOLEAN' },
        answer: { type: 'STRING' },
        citations: { type: 'ARRAY', items: { type: 'STRING' } },
    },
    required: ['answerable', 'answer', 'citations'],
};

/**
 * Google's generateContent API with JSON output. Uses GEMINI_API_KEY, or the
 * embedding key the project already has. Billed per request on that key.
 */
export function geminiProvider(fetchImpl: typeof fetch = fetch): CopilotProvider {
    return {
        name: 'gemini',
        async answer(system, userText) {
            const key = env.GEMINI_API_KEY ?? env.EMBEDDING_API_KEY;
            const url = `https://generativelanguage.googleapis.com/v1beta/models/${env.COPILOT_GEMINI_MODEL}:generateContent?key=${encodeURIComponent(key)}`;
            let response: Response;
            try {
                response = await fetchImpl(url, {
                    method: 'POST',
                    signal: AbortSignal.timeout(env.COPILOT_TIMEOUT_MS),
                    headers: { 'content-type': 'application/json' },
                    body: JSON.stringify({
                        systemInstruction: { parts: [{ text: system }] },
                        contents: [{ role: 'user', parts: [{ text: userText }] }],
                        generationConfig: {
                            temperature: 0,
                            maxOutputTokens: env.COPILOT_MAX_TOKENS,
                            responseMimeType: 'application/json',
                            responseSchema: GEMINI_SCHEMA,
                        },
                    }),
                });
            } catch {
                throw upstream('The Copilot provider could not be reached');
            }
            if (response.status === 401 || response.status === 403) {
                throw unavailable('The Copilot is not configured correctly on this server');
            }
            if (response.status === 429) throw busy();
            if (!response.ok) throw upstream(`The Copilot provider failed (${response.status})`);

            const body = (await response.json()) as {
                candidates?: { content?: { parts?: { text?: string }[] }; finishReason?: string }[];
                promptFeedback?: { blockReason?: string };
                modelVersion?: string;
            };
            const candidate = body.candidates?.[0];
            const finish = candidate?.finishReason;
            const refused =
                Boolean(body.promptFeedback?.blockReason) || finish === 'SAFETY' || finish === 'PROHIBITED_CONTENT';
            return {
                output: refused ? null : parseOutput(candidate?.content?.parts?.map((p) => p.text ?? '').join('')),
                refused,
                truncated: finish === 'MAX_TOKENS',
                model: body.modelVersion ?? env.COPILOT_GEMINI_MODEL,
            };
        },
    };
}

/** The provider COPILOT_PROVIDER names, or null when the Copilot is switched off. */
export function providerFromEnv(): CopilotProvider | null {
    switch (env.COPILOT_PROVIDER) {
        case 'anthropic':
            return anthropicProvider();
        case 'ollama':
            return ollamaProvider();
        case 'gemini':
            return geminiProvider();
        default:
            return null;
    }
}
