import { pool } from '../db/pool.ts';
import { env } from '../config/env.ts';

/**
 * The in-flight guard for POST /complaints/:id/process.
 *
 * A single atomic compare-and-swap moves the complaint to PROCESSING. Whoever
 * wins the swap owns the run; a concurrent /process call (the reconcile job
 * firing while the original trigger is still working, a manual retry) gets
 * `claimed: false` and exits. This is the only thing standing between two Node
 * runs both POSTing to /internal/incidents/attach for the same complaint.
 *
 * `matching_status` is uppercase in the DB (@Enumerated(STRING) on the Spring
 * entity). Node owns exactly the PENDING/DEGRADED -> PROCESSING claim and the
 * PROCESSING -> previous release; Spring owns every terminal write. See
 * docs/service-boundaries.md.
 */

export interface ClaimResult {
    claimed: boolean;
    /** The status the row held before the claim — restore target for release(). */
    previousStatus: string | null;
}

// The pre-image MUST be read before the UPDATE writes, or release() would
// restore PROCESSING->PROCESSING and strand the row. A FOR UPDATE CTE,
// evaluated first, is what makes that correct.
const CLAIM_SQL = `
    WITH prev AS (
        SELECT matching_status
        FROM complaints
        WHERE id = $1
          AND ( matching_status IN ('PENDING', 'DEGRADED')
                OR ($2::bool AND matching_status = 'MATCHED')
                OR ( matching_status = 'PROCESSING'
                     AND updated_at < now() - make_interval(secs => $3::int) ) )
        FOR UPDATE
    )
    UPDATE complaints c
    SET matching_status = 'PROCESSING', updated_at = now()
    FROM prev
    WHERE c.id = $1
    RETURNING prev.matching_status AS previous_status
`;

export async function claim(
    complaintId: string,
    opts: { force: boolean },
): Promise<ClaimResult> {
    const result = await pool.query<{ previous_status: string }>(CLAIM_SQL, [
        complaintId,
        opts.force,
        env.PROCESSING_STALE_SECONDS,
    ]);
    const row = result.rows[0];
    return row
        ? { claimed: true, previousStatus: row.previous_status }
        : { claimed: false, previousStatus: null };
}

/**
 * Restores the pre-claim status after a run fails, so the reconcile job picks
 * the complaint up again instead of waiting out the stale window. Scoped to
 * `matching_status = 'PROCESSING'` so it cannot stomp a terminal state that
 * Spring wrote in the meantime.
 */
export async function release(complaintId: string, toStatus: string): Promise<void> {
    await pool.query(
        `UPDATE complaints
         SET matching_status = $2, updated_at = now()
         WHERE id = $1 AND matching_status = 'PROCESSING'`,
        [complaintId, toStatus],
    );
}
