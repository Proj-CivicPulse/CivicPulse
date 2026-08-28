import { get, post, patch } from './api';
import { SPRING_API_PATH } from '../config/constants';

export type ComplaintStatus = 'open' | 'in_progress' | 'resolved' | 'closed';

export interface Complaint {
    id: string;
    category: string;
    wardId: string;
    lat: number;
    long: number;
    description: string;
    photoUrl?: string;
    status: ComplaintStatus;
    incidentId: string | null;
    createdAt: string;
}

export interface CreateComplaintInput {
    category: string;
    wardId: string;
    lat: number;
    long: number;
    description: string;
    // TODO: shape depends on the still-open photo-upload decision in
    // docs/endpoints.md — assumes direct-to-storage upload producing a URL,
    // not a multipart endpoint. Update this if the team decides otherwise.
    photoUrl?: string;
}

export interface ComplaintFilters {
    wardId?: string;
    category?: string;
    status?: ComplaintStatus;
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
    create: (input: CreateComplaintInput) => post<Complaint>(SPRING_API_PATH, '/complaints', input),

    list: (filters?: ComplaintFilters) => get<Complaint[]>(SPRING_API_PATH, `/complaints${buildQuery(filters)}`),

    getById: (id: string) => get<Complaint>(SPRING_API_PATH, `/complaints/${id}`),

    // TODO: requires citizen auth — open decision in docs/endpoints.md on
    // whether complaint submission/tracking requires an account at all.
    mine: () => get<Complaint[]>(SPRING_API_PATH, '/complaints/mine'),

    updateStatus: (id: string, status: ComplaintStatus) =>
        patch<Complaint>(SPRING_API_PATH, `/complaints/${id}`, { status }),
};