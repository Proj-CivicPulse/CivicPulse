/**
 * TanStack Query key factory.
 *
 * Replaces the ad-hoc literals that were scattered across call sites
 * (['incidents'], ['complaints','mine'], ['health','node']). Having them in one
 * place is what makes invalidation after a mutation reliable — an invalidation
 * that misspells its key fails silently and leaves stale data on screen.
 *
 * Keys are hierarchical: invalidating `incidents.all()` also invalidates every
 * filtered list and detail beneath it.
 */

import type { ComplaintFilters } from '@/services/complaint.service';
import type { IncidentFilters } from '@/services/incident.service';

export const queryKeys = {
    health: {
        spring: () => ['health', 'spring'] as const,
        node: () => ['health', 'node'] as const,
    },

    auth: {
        me: () => ['auth', 'me'] as const,
    },

    wards: {
        all: () => ['wards'] as const,
        list: () => ['wards', 'list'] as const,
        detail: (id: string) => ['wards', 'detail', id] as const,
        summary: () => ['wards', 'summary'] as const,
        resolve: (lat: number, long: number) => ['wards', 'resolve', lat, long] as const,
    },

    departments: {
        all: () => ['departments'] as const,
        list: () => ['departments', 'list'] as const,
    },

    complaints: {
        all: () => ['complaints'] as const,
        list: (filters?: ComplaintFilters) => ['complaints', 'list', filters ?? {}] as const,
        mine: () => ['complaints', 'mine'] as const,
        detail: (id: string) => ['complaints', 'detail', id] as const,
    },

    incidents: {
        all: () => ['incidents'] as const,
        list: (filters?: IncidentFilters) => ['incidents', 'list', filters ?? {}] as const,
        detail: (id: string) => ['incidents', 'detail', id] as const,
        complaints: (id: string) => ['incidents', 'detail', id, 'complaints'] as const,
    },

    dashboard: {
        all: () => ['dashboard'] as const,
        summary: () => ['dashboard', 'summary'] as const,
        ward: (wardId: string) => ['dashboard', 'ward', wardId] as const,
    },
} as const;
