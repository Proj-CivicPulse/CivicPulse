import { env } from '../config/env.ts';
import { logger } from '../config/logger.ts';
import { HttpError } from '../middleware/errorHandler.ts';

/**
 * Thin client for backend-spring's /internal/* endpoints. Spring is the single
 * writer and schema owner (docs/service-boundaries.md); this service reads
 * complaint context from it and hands back a match decision for Spring to
 * persist.
 *
 * Every call carries X-Internal-Token and is bounded by a timeout — a hung
 * Spring must surface as an error here, not as a stuck request thread.
 */

const TIMEOUT_MS = 8000;

/** The subset of Spring's ComplaintDto this service needs. Ids are strings on the wire. */
export interface SpringComplaint {
    id: string;
    wardId: string;
    incidentId: string | null;
    title: string | null;
    description: string;
    category: string;
    matchingStatus: 'pending' | 'processing' | 'matched' | 'degraded';
}

export interface AttachPayload {
    complaintId: string;
    /** null => start a new incident. */
    incidentId: string | null;
    topSimilarity: number | null;
    topSiblingComplaintId: string | null;
    candidateCount: number;
    threshold: number;
    model: string;
    embeddingDim: number;
}

export interface AttachResult {
    incidentId: string;
    created: boolean;
    priorityScore: number | null;
    matchingStatus: string;
}

async function call<T>(path: string, init: RequestInit): Promise<T> {
    let response: Response;
    try {
        response = await fetch(`${env.SPRING_INTERNAL_BASE_URL}${path}`, {
            ...init,
            signal: AbortSignal.timeout(TIMEOUT_MS),
            headers: {
                'content-type': 'application/json',
                'x-internal-token': env.INTERNAL_TOKEN,
                ...init.headers,
            },
        });
    } catch (err) {
        logger.error({ err, path }, 'backend-spring internal call failed');
        throw new HttpError(502, 'CORE_UPSTREAM_ERROR', 'backend-spring is unreachable');
    }

    if (!response.ok) {
        const body = await response.text().catch(() => '');
        logger.error({ path, status: response.status, body }, 'backend-spring internal call errored');
        throw new HttpError(
            502,
            'CORE_UPSTREAM_ERROR',
            `backend-spring responded ${response.status}`,
        );
    }

    return response.json() as Promise<T>;
}

export const springClient = {
    getComplaint(id: string): Promise<SpringComplaint> {
        return call<SpringComplaint>(`/internal/complaints/${encodeURIComponent(id)}`, {
            method: 'GET',
        });
    },

    attach(payload: AttachPayload): Promise<AttachResult> {
        return call<AttachResult>('/internal/incidents/attach', {
            method: 'POST',
            body: JSON.stringify(payload),
        });
    },
};
