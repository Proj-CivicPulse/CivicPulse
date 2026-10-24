import { get, post, patch, type Page } from './api';
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
    /** A photo is attached. Its URL is never on this shape — see complaintService.photo. */
    hasPhoto: boolean;
    /**
     * Only on the create response, and only when photo upload is on: the
     * proof that lets whoever just filed this report attach its photo
     * without an account. Short-lived; keep it in memory, never in storage.
     */
    photoUploadToken?: string | null;
    /**
     * When the problem was reported. Equal to createdAt for a complaint filed
     * through this site; the upstream timestamp for an imported one, which can
     * be far older than the row. Render this for "reported N days ago".
     */
    reportedAt: string;
    /** When the row was created here. Use for arrival order, not for age. */
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
    // No photoUrl. The server never accepts one on create: a caller-supplied
    // URL ends up rendered in an officer's dashboard, which makes it a tracking
    // pixel at best. Photos arrive through the server-issued intent/confirm
    // flow instead (complaintService.photoIntent / confirmPhoto).
}

/** GET /complaints parameters (docs/api-contract.md, E4). Newest report first by default. */
export interface ComplaintFilters {
    wardIds?: string[];
    categories?: string[];
    statuses?: ComplaintStatus[];
    /** ISO-8601 instants bounding reportedAt, [from, to). */
    from?: string;
    to?: string;
    q?: string;
    dir?: 'asc' | 'desc';
    /** Server default 50, server max 200. */
    limit?: number;
    offset?: number;
}

export interface UpdateComplaintInput {
    status?: ComplaintStatus;
    departmentId?: string;
}

function buildQuery(filters: ComplaintFilters = {}): string {
    const params = new URLSearchParams();
    filters.wardIds?.forEach((id) => params.append('wardId', id));
    filters.categories?.forEach((code) => params.append('category', code));
    filters.statuses?.forEach((status) => params.append('status', status));
    if (filters.from) params.set('from', filters.from);
    if (filters.to) params.set('to', filters.to);
    if (filters.q) params.set('q', filters.q);
    if (filters.dir) params.set('dir', filters.dir);
    if (filters.limit !== undefined) params.set('limit', String(filters.limit));
    if (filters.offset !== undefined) params.set('offset', String(filters.offset));
    const query = params.toString();
    return query ? `?${query}` : '';
}

export const complaintService = {
    /** Public — submission does not require an account (docs/endpoints.md). */
    create: (input: CreateComplaintInput) =>
        post<Complaint>(SPRING_API_PATH, '/complaints', input),

    /** Officer only — this is every citizen's complaint text. */
    list: (filters?: ComplaintFilters) =>
        get<Page<Complaint>>(SPRING_API_PATH, `/complaints${buildQuery(filters)}`),

    getById: (id: string) => get<Complaint>(SPRING_API_PATH, `/complaints/${id}`),

    /** Citizen role required. Tracking is what an account buys you. */
    mine: () => get<Complaint[]>(SPRING_API_PATH, '/complaints/mine'),

    /** Officer only. */
    update: (id: string, changes: UpdateComplaintInput) =>
        patch<Complaint>(SPRING_API_PATH, `/complaints/${id}`, changes),

    /** Step 1 of the photo flow: permission to PUT one photo straight to storage. */
    photoIntent: (id: string, body: { contentType: string; sizeBytes: number; uploadToken?: string }) =>
        post<PhotoIntent>(SPRING_API_PATH, `/complaints/${id}/photo-intent`, body),

    /** Step 3: the server checks what arrived, strips its metadata and attaches it. */
    confirmPhoto: (id: string, body: { objectKey: string; uploadToken?: string }) =>
        post<Complaint>(SPRING_API_PATH, `/complaints/${id}/photo`, body),

    /** Officers and the owner: short-lived signed links, minted per request. */
    photo: (id: string) => get<PhotoLinks>(SPRING_API_PATH, `/complaints/${id}/photo`),
};

/** docs/photo-upload-contract.md §2. */
export interface PhotoIntent {
    uploadUrl: string;
    objectKey: string;
    expiresAt: string;
    maxBytes: number;
    contentType: string;
}

export interface PhotoLinks {
    url: string;
    thumbnailUrl: string;
    expiresAt: string;
}

export const PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const;
export const PHOTO_MAX_BYTES = 8 * 1024 * 1024;
