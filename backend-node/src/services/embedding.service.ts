/**
 * embedding.service — Phase 2 (not implemented)
 *
 * Responsibility: turn complaint text into a vector and persist it so the
 * matching service can compare against it.
 *
 * Planned shape:
 *   generateEmbedding(text: string): Promise<number[]>
 *     → calls the external embedding provider (EMBEDDING_API_KEY)
 *   storeEmbedding(complaintId: string, vector: number[]): Promise<void>
 *     → parameterized UPDATE into a pgvector column:
 *         UPDATE complaint SET embedding = $1 WHERE id = $2
 *       NEVER interpolate the vector or id into the SQL string.
 *
 * Depends on: docs/api-contract.md (GET /internal/complaints/:id for text),
 * docs/service-boundaries.md (open questions before Phase 3).
 */

export {};
