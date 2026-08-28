import { get, post, patch } from './api';
import { SPRING_API_PATH } from '../config/constants';
import type { Complaint } from './complaint.service';

export type IncidentStatus = 'open' | 'in_progress' | 'resolved' | 'closed';

export interface Incident {
    id: string;
    wardId: string;
    category: string;
    priorityScore: number;
    priorityReasons: string[];
    status: IncidentStatus;
    createdAt: string;
    updatedAt: string;
}

export interface IncidentFilters {
    wardId?: string;
    category?: string;
    status?: IncidentStatus;
    minPriority?: number;
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

export const incidentService = {
    list: (filters?: IncidentFilters) => get<Incident[]>(SPRING_API_PATH, `/incidents${buildQuery(filters)}`),

    getById: (id: string) => get<Incident>(SPRING_API_PATH, `/incidents/${id}`),

    getComplaints: (id: string) => get<Complaint[]>(SPRING_API_PATH, `/incidents/${id}/complaints`),

    update: (id: string, changes: Partial<Pick<Incident, 'status'>>) =>
        patch<Incident>(SPRING_API_PATH, `/incidents/${id}`, changes),

    // Manual overrides — Risk Watchlist #2 in the roadmap: officers need a
    // way to correct false merges and false negatives by hand.
    merge: (targetIncidentId: string, complaintId: string) =>
        post<void>(SPRING_API_PATH, `/incidents/${targetIncidentId}/merge`, { complaintId }),

    unlinkComplaint: (incidentId: string, complaintId: string) =>
        post<void>(SPRING_API_PATH, `/incidents/${incidentId}/complaints/${complaintId}/unlink`, undefined),
};