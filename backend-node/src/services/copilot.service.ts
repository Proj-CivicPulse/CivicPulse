/**
 * copilot.service — Phase 6 (not implemented)
 *
 * Responsibility: answer an officer's question using ONLY CivicPulse's own
 * retrieved data — never the open web, never uncited model knowledge.
 *
 * Planned shape:
 *   answerQuery(input: { officerId: string; query: string; wardId?: string })
 *     : Promise<{ answer: string; sources: string[] }>
 *   Steps:
 *     1. classify intent from the query
 *     2. retrieve grounding rows from Postgres via parameterized queries
 *        (incidents, complaints, trends) — $1, $2, ... only
 *     3. call the external LLM (LLM_API_KEY) with that context injected
 *     4. return the answer plus the source ids it was grounded on
 *
 * Cost control: cache answers where reasonable; this route also needs its
 * own strict rate limiter (see src/middleware/rateLimiter.ts).
 */

export {};
