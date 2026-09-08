/**
 * embedding.service — Phase 2
 *
 * Turns complaint text into a vector (Gemini gemini-embedding-001) and persists
 * it so matching.service can compare against it with pgvector.
 *
 * gemini-embedding-001 only pre-normalises the full 3072-dim output; at any
 * smaller `outputDimensionality` the caller must L2-normalise, which is done
 * here so every stored vector is unit length and `1 - (a <=> b)` is a true
 * cosine similarity.
 */

import { env } from '../config/env.ts';
import { logger } from '../config/logger.ts';
import { pool } from '../db/pool.ts';
import { HttpError } from '../middleware/errorHandler.ts';

const ENDPOINT = `https://generativelanguage.googleapis.com/v1beta/models/${env.EMBEDDING_MODEL}:embedContent`;

interface EmbedResponse {
    embedding?: { values?: number[] };
}

/** `[category] title\n description` — category is already a hard pre-filter, so this just sharpens it. */
export function buildEmbeddingText(input: {
    category: string;
    title: string | null;
    description: string;
}): string {
    const title = input.title?.trim() ? `${input.title.trim()}\n` : '';
    return `[${input.category}] ${title}${input.description}`.trim();
}

function l2normalize(vector: number[]): number[] {
    let sumSquares = 0;
    for (const value of vector) {
        sumSquares += value * value;
    }
    const norm = Math.sqrt(sumSquares);
    if (norm === 0) {
        return vector;
    }
    return vector.map((value) => value / norm);
}

export async function generateEmbedding(text: string): Promise<number[]> {
    let response: Response;
    try {
        response = await fetch(`${ENDPOINT}?key=${encodeURIComponent(env.EMBEDDING_API_KEY)}`, {
            method: 'POST',
            signal: AbortSignal.timeout(env.EMBEDDING_TIMEOUT_MS),
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({
                model: `models/${env.EMBEDDING_MODEL}`,
                content: { parts: [{ text }] },
                taskType: env.EMBEDDING_TASK_TYPE,
                outputDimensionality: env.EMBEDDING_DIMENSIONS,
            }),
        });
    } catch (err) {
        logger.error({ err }, 'embedding provider request failed');
        throw new HttpError(502, 'EMBEDDING_UPSTREAM_ERROR', 'The embedding provider is unreachable');
    }

    if (!response.ok) {
        const body = await response.text().catch(() => '');
        // The provider echoes the API key in no field, but its error bodies can
        // be verbose — log server-side only, never to the client.
        logger.error({ status: response.status, body }, 'embedding provider returned an error');
        throw new HttpError(
            502,
            'EMBEDDING_UPSTREAM_ERROR',
            `The embedding provider responded ${response.status}`,
        );
    }

    const data = (await response.json()) as EmbedResponse;
    const values = data.embedding?.values;
    if (!Array.isArray(values) || values.length !== env.EMBEDDING_DIMENSIONS) {
        logger.error(
            { got: values?.length, expected: env.EMBEDDING_DIMENSIONS },
            'embedding provider returned an unexpected vector',
        );
        throw new HttpError(502, 'EMBEDDING_UPSTREAM_ERROR', 'The embedding provider returned a malformed vector');
    }

    return l2normalize(values);
}

/**
 * Persists the vector. Parameterised — the vector goes in as a `[a,b,c]` text
 * literal bound to $1 and cast to `vector` by Postgres, never interpolated into
 * the SQL string.
 */
export async function storeEmbedding(complaintId: string, vector: number[]): Promise<void> {
    await pool.query('UPDATE complaints SET embedding = $1::vector WHERE id = $2', [
        `[${vector.join(',')}]`,
        complaintId,
    ]);
}

/**
 * Reads back a previously stored vector, or null if the complaint has none.
 * `embedding::text` renders as `[a,b,c]`, which is valid JSON. Lets a reprocess
 * skip the provider call when the text has not changed.
 */
export async function readEmbedding(complaintId: string): Promise<number[] | null> {
    const result = await pool.query<{ e: string | null }>(
        'SELECT embedding::text AS e FROM complaints WHERE id = $1',
        [complaintId],
    );
    const raw = result.rows[0]?.e;
    if (!raw) {
        return null;
    }
    try {
        return JSON.parse(raw) as number[];
    } catch {
        return null;
    }
}
