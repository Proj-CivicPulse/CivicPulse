import { useQuery } from '@tanstack/react-query';
import { incidentService } from '../services/incident.service';

export default function IncidentDashboard() {
    const incidents = useQuery({
        queryKey: ['incidents'],
        queryFn: () => incidentService.list(),
    });

    return (
        <div>
            <h1>Incident Dashboard</h1>
            {incidents.isPending && <p>Loading incidents…</p>}
            {incidents.isError && <p>Couldn't load incidents. Try again later.</p>}
            {incidents.data && incidents.data.length === 0 && <p>No incidents yet.</p>}
            {incidents.data && incidents.data.length > 0 && (
                <ul>
                    {incidents.data.map((i) => (
                        <li key={i.id}>
                            {i.category} — Ward {i.wardId} — priority {i.priorityScore} — {i.status}
                        </li>
                    ))}
                </ul>
            )}
            {/* TODO: Leaflet map with markers colored by priority goes here (Phase 4) */}
        </div>
    );
}