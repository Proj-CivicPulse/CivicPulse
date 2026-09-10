import { get, post, patch } from './api';
import { SPRING_API_PATH } from '../config/constants';

export type ComplaintStatus = 'open' | 'in_progress' | 'resolved' | 'closed';

/**
 * Where a complaint sits in the Phase 2 incident-matching pipeline. Distinct
 * from `ComplaintStatus`, which is the civic lifecycle an officer drives.
 *
 * - `pending`    — submitted; matching has not run yet. Every freshly created
 *                  complaint is this, because matching happens after the
 *                  response is sent.
 * - `processing` — backend-node is embedding and matching it right now.
 * - `matched`    — the semantic matcher assigned it, to a new or existing incident.
 * - `degraded`   — backend-node was unreachable, so the naive ward+category
 *                  fallback grouped it. Spring's reconciliation job upgrades
 *                  these to `matched` once Node recovers.
 */
export type MatchingStatus = 'pending' | 'processing' | 'matched' | 'degraded';

/**
 * Wire shape per docs/api-contract.md. Ids are strings, enums lowercase, and
 * the DB's latitude/longitude columns arrive as lat/long.
 */
export interface Complaint {
    id: string;
    /** Human-readable receipt, e.g. "CP-2026-W17-00412". Generated on insert. */
    referenceNo: string;
    /** null when the report was submitted anonymously. */
    userId: string | null;
    wardId: string;
    /** null until the matcher attaches this complaint to an incident. */
    incidentId: string | null;
    /**
     * Total complaints in this one's incident, including this one. null when
     * unmatched.
     *
     * Carried on the complaint rather than read from GET /incidents/{id},
     * which is officer-only — a citizen cannot fetch incidents at all, so this
     * is the only route by which "Grouped with N other reports" can reach the
     * My reports screen. It is an aggregate count and leaks no other incident
     * detail.
     */
    incidentComplaintCount: number | null;
    /** null until an officer triages it. */
    departmentId: string | null;
    title?: string;
    description: string;
    category: string;
    lat: number;
    long: number;
    /**
     * Street address for the coordinates. Null when geocoding is disabled or
     * the point has no address — always render coordinates as the fallback.
     */
    address: string | null;
    status: ComplaintStatus;
    /**
     * Pipeline state, not civic state — see {@link MatchingStatus}.
     *
     * Rendered on the My reports screen via `formatMatchingSummary`, but only
     * as readiness, never as mechanism: a resident is told whether the grouping
     * is still being worked out, not which matcher produced it or what it
     * scored. That keeps the copy independent of the similarity threshold,
     * which is still being tuned — anything that exposed a score or named the
     * strategy would be the first thing to go stale.
     *
     * Officer-side use, where the mechanism does matter, lands with the
     * dashboard work.
     */
    matchingStatus: MatchingStatus;
    photoUrl?: string;
    createdAt: string;
    updatedAt: string;
}

export interface CreateComplaintInput {
    title?: string;
    description: string;
    category: string;
    /**
     * Optional. When omitted the server derives the ward from lat/long via
     * GET /wards/resolve, which is the path the submit flow uses — the system
     * knowing your ward from the pin is the trust moment, not a dropdown.
     */
    wardId?: string;
    lat: number;
    long: number;
    /**
     * The upload mechanism is still an open decision in docs/endpoints.md
     * (direct-to-storage URL vs. a multipart endpoint on Spring). No storage
     * provider is configured, so the submit form ships this block disabled
     * rather than shipping a fake uploader.
     */
    photoUrl?: string;
}

export interface ComplaintFilters {
    wardId?: string;
    category?: string;
    status?: ComplaintStatus;
}

export interface UpdateComplaintInput {
    status?: ComplaintStatus;
    departmentId?: string;
}

function buildQuery(filters: ComplaintFilters = {}): string {
    const params = new URLSearchParams();
    if (filters.wardId) params.set('wardId', filters.wardId);
    if (filters.category) params.set('category', filters.category);
    if (filters.status) params.set('status', filters.status);
    const query = params.toString();
    return query ? `?${query}` : '';
}

export const complaintService = {
    /** Public — submission does not require an account (docs/endpoints.md). */
    create: (input: CreateComplaintInput) =>
        post<Complaint>(SPRING_API_PATH, '/complaints', input),

    /** Officer only — this is every citizen's complaint text. */
    list: (filters?: ComplaintFilters) =>
        get<Complaint[]>(SPRING_API_PATH, `/complaints${buildQuery(filters)}`),

    getById: (id: string) => get<Complaint>(SPRING_API_PATH, `/complaints/${id}`),

    /** Citizen role required. Tracking is what an account buys you. */
    mine: () => get<Complaint[]>(SPRING_API_PATH, '/complaints/mine'),

    /** Officer only. */
    update: (id: string, changes: UpdateComplaintInput) =>
        patch<Complaint>(SPRING_API_PATH, `/complaints/${id}`, changes),
};
