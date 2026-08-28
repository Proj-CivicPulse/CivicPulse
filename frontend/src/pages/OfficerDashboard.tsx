import { useQuery } from '@tanstack/react-query';
import { healthService } from '../services/health.service';

export default function OfficerDashboard() {
    const { data, isPending, isError } = useQuery({
        queryKey: ['health', 'node'],
        queryFn: ({ signal }) => healthService.checkNode({ signal }),
    });

    const status = isPending ? 'checking' : isError ? 'error' : (data?.status ?? 'error');

    return (
        <div>
            <h1>Officer Dashboard</h1>
            <p>Node AI service status: {status}</p>
            {/* TODO: map + incident drill-down goes here (Phase 4) */}
        </div>
    );
}