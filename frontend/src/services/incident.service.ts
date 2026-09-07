import { get, patch } from './api';
import { SPRING_API_PATH } from '../config/constants';
import type { Complaint } from './complaint.service';
import type { PriorityBand } from '@/lib/priority';

export type IncidentStatus = 'open' | 'in_progress' | 'resolved' | 'closed';

/**
 * Wire shape per docs/api-contract.md.
 *
 * An incident is the unit officers act on: a group of related complaints
 * carrying a priority score AND the human-readable reasons for it.
 */
export interface Incident {
    id: string;
    wardId: string;
    departmentId: string | null;
    title: string | null;
    summary: string | null;
    category: string;
    priorityScore: number;
    /** Computed server-side; the thresholds belong to the model, not the client. */
    priorityBand: PriorityBand;
    /**
     * The project's headline explainability claim. Human-readable evidence
     * strings, never codes and never weights. Render them; if the array is
     * empty, say so explicitly rather than hiding the section.
     */
    priorityReasons: string[];
    status: IncidentStatus;
    /** Street address of the centroid. Null when geocoding is disabled. */
    address: string | null;
    complaintCount: number;
    lat: number | null;
    long: number | null;
    /** Node writes this in Phase 6. Null until then. */
    aiRecommendation: string | null;
    createdAt: string;
    updatedAt: string;
}

export interface IncidentFilters {
    wardId?: string;
    category?: string;
    status?: IncidentStatus;
    minPriority?: number;
}

export interface UpdateIncidentInput {
    status?: IncidentStatus;
    departmentId?: string;
    title?: string;
    summary?: string;
}

function buildQuery(filters: IncidentFilters = {}): string {
    const params = new URLSearchParams();
    if (filters.wardId) params.set('wardId', filters.wardId);
    if (filters.category) params.set('category', filters.category);
    if (filters.status) params.set('status', filters.status);
    if (filters.minPriority !== undefined) params.set('minPriority', String(filters.minPriority));
    const query = params.toString();
    return query ? `?${query}` : '';
}

/** Every endpoint here requires the officer role. */
export const incidentService = {
    list: (filters?: IncidentFilters) =>
        get<Incident[]>(SPRING_API_PATH, `/incidents${buildQuery(filters)}`),

    getById: (id: string) => get<Incident>(SPRING_API_PATH, `/incidents/${id}`),

    getComplaints: (id: string) =>
        get<Complaint[]>(SPRING_API_PATH, `/incidents/${id}/complaints`),

    update: (id: string, changes: UpdateIncidentInput) =>
        patch<Incident>(SPRING_API_PATH, `/incidents/${id}`, changes),
};
