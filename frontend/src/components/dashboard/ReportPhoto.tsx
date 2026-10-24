import { useQuery } from '@tanstack/react-query';
import { complaintService } from '@/services/complaint.service';
import { queryKeys } from '@/lib/queryKeys';
import styles from './IncidentDetail.module.css';

/**
 * A report's photo thumbnail, linking to the full image. The links are
 * signed and expire (15 min), so they are fetched per view and refetched
 * well before they lapse — never stored.
 */
export default function ReportPhoto({ complaintId }: { complaintId: string }) {
    const query = useQuery({
        queryKey: queryKeys.incidents.photo(complaintId),
        queryFn: () => complaintService.photo(complaintId),
        staleTime: 10 * 60_000,
        refetchInterval: 10 * 60_000,
    });
    if (!query.data) {
        return query.isError ? <span className={styles.complaintWhy}>Photo unavailable.</span> : null;
    }
    return (
        <a className={styles.photoLink} href={query.data.url} target="_blank" rel="noreferrer noopener">
            <img
                className={styles.photoThumb}
                src={query.data.thumbnailUrl}
                alt="Photo attached to this report"
                loading="lazy"
                referrerPolicy="no-referrer"
            />
        </a>
    );
}
