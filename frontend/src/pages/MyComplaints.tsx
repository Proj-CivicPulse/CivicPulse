import { useQuery } from '@tanstack/react-query';
import { complaintService } from '../services/complaint.service';

export default function MyComplaints() {
    const complaints = useQuery({
        queryKey: ['complaints', 'mine'],
        queryFn: () => complaintService.mine(),
    });

    return (
        <div>
            <h1>My Complaints</h1>
            {complaints.isPending && <p>Loading your complaints…</p>}
            {complaints.isError && <p>Couldn't load complaints. Try again later.</p>}
            {complaints.data && complaints.data.length === 0 && <p>You haven't submitted any complaints yet.</p>}
            {complaints.data && complaints.data.length > 0 && (
                <ul>
                    {complaints.data.map((c) => (
                        <li key={c.id}>
                            {c.category} — {c.status} — {c.description}
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}