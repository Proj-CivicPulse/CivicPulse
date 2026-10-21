import { create } from 'zustand';
import type { IncidentStatus } from '@/services/incident.service';
import type { PriorityBand } from '@/lib/priority';
import type { WindowDays } from '@/lib/reportWindow';

/**
 * Officer console and Explorer CLIENT state only — which ward is selected,
 * which incident is open, and what the filters are set to.
 *
 * One filter state for every Explorer view: the incident queue,
 * the ward and category explorers and their detail pages all read these, so
 * moving between them keeps the slice the officer was looking at. Each view
 * applies the subset its endpoint accepts.
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
    /** "Reported within" — null is all time. Applies to every Explorer view. */
    windowDays: WindowDays | null;

    selectWard: (wardId: string | null) => void;
    selectIncident: (incidentId: string | null) => void;
    setStatusFilter: (status: IncidentStatus | null) => void;
    setBandFilter: (band: PriorityBand | null) => void;
    setCategoryFilter: (category: string | null) => void;
    setWindowDays: (days: WindowDays | null) => void;
    clearFilters: () => void;
}

export const useDashboardStore = create<DashboardState>((set) => ({
    selectedWardId: null,
    selectedIncidentId: null,
    statusFilter: null,
    bandFilter: null,
    categoryFilter: null,
    windowDays: null,

    // Changing ward clears the open incident: the detail panel would otherwise
    // keep showing an incident that is no longer in the filtered set.
    selectWard: (wardId) => set({ selectedWardId: wardId, selectedIncidentId: null }),

    selectIncident: (incidentId) => set({ selectedIncidentId: incidentId }),

    setStatusFilter: (status) => set({ statusFilter: status, selectedIncidentId: null }),
    setBandFilter: (band) => set({ bandFilter: band, selectedIncidentId: null }),
    setCategoryFilter: (category) => set({ categoryFilter: category, selectedIncidentId: null }),
    setWindowDays: (days) => set({ windowDays: days, selectedIncidentId: null }),

    clearFilters: () =>
        set({
            statusFilter: null,
            bandFilter: null,
            categoryFilter: null,
            windowDays: null,
            selectedIncidentId: null,
        }),
}));
