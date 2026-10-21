import { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDashboardStore } from '@/stores/dashboard.store';

/**
 * Opens an incident in the queue — the console is where incidents are worked,
 * so the Explorer links into it rather than growing a second incident panel.
 *
 * The queue's filters are cleared first, or the incident might be filtered
 * out of the very list it is being opened in.
 */
export function useOpenIncident(): (incidentId: string, wardId: string) => void {
    const navigate = useNavigate();
    const clearFilters = useDashboardStore((s) => s.clearFilters);
    const selectWard = useDashboardStore((s) => s.selectWard);
    const selectIncident = useDashboardStore((s) => s.selectIncident);

    return useCallback(
        (incidentId: string, wardId: string) => {
            clearFilters();
            selectWard(wardId);
            selectIncident(incidentId);
            navigate('/dashboard');
        },
        [clearFilters, selectWard, selectIncident, navigate]
    );
}
