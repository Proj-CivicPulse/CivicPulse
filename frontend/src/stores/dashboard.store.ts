import { create } from 'zustand';
import type { IncidentStatus } from '@/services/incident.service';
import type { PriorityBand } from '@/lib/priority';

/**
 * Officer console CLIENT state only — which ward is selected, which incident is
 * open, and what the filters are set to.
 *
 * No server data lives here. Incidents and complaints belong to TanStack Query,
 * which owns their caching, refetching, and invalidation. Mirroring them into
 * Zustand is how two sources of truth start disagreeing.
 */
interface DashboardState {
    selectedWardId: string | null;
    selectedIncidentId: string | null;
    statusFilter: IncidentStatus | null;
    bandFilter: PriorityBand | null;
    categoryFilter: string | null;

    selectWard: (wardId: string | null) => void;
    selectIncident: (incidentId: string | null) => void;
    setStatusFilter: (status: IncidentStatus | null) => void;
    setBandFilter: (band: PriorityBand | null) => void;
    setCategoryFilter: (category: string | null) => void;
    clearFilters: () => void;
}

export const useDashboardStore = create<DashboardState>((set) => ({
    selectedWardId: null,
    selectedIncidentId: null,
    statusFilter: null,
    bandFilter: null,
    categoryFilter: null,

    // Changing ward clears the open incident: the detail panel would otherwise
    // keep showing an incident that is no longer in the filtered set.
    selectWard: (wardId) => set({ selectedWardId: wardId, selectedIncidentId: null }),

    selectIncident: (incidentId) => set({ selectedIncidentId: incidentId }),

    setStatusFilter: (status) => set({ statusFilter: status, selectedIncidentId: null }),
    setBandFilter: (band) => set({ bandFilter: band, selectedIncidentId: null }),
    setCategoryFilter: (category) => set({ categoryFilter: category, selectedIncidentId: null }),

    clearFilters: () =>
        set({
            statusFilter: null,
            bandFilter: null,
            categoryFilter: null,
            selectedIncidentId: null,
        }),
}));
