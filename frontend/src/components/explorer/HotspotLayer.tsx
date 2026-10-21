import { Circle, Tooltip } from 'react-leaflet';
import type { Hotspot } from '@/services/explorer.service';
import { priorityLabel } from '@/lib/priority';
import { formatCount } from '@/lib/format';
import styles from './HotspotLayer.module.css';

/** Padding around the farthest member, so the ring encloses its markers. */
const RING_PADDING_METRES = 60;
/** A hotspot whose members nearly coincide still reads as an area. */
const MIN_RING_METRES = 120;

const BAND_CLASS = {
    low: styles.low,
    medium: styles.medium,
    high: styles.high,
    critical: styles.critical,
} as const;

interface Props {
    hotspots: readonly Hotspot[];
    categoryLabel: (code: string) => string;
    onSelect?: (hotspot: Hotspot) => void;
}

/**
 * Hotspots as rings over the ward map, one per hotspot, edged in the colour of
 * its highest-priority member. Colour is the second channel: the tooltip
 * names the band in words.
 *
 * Colours are CSS classes (see WardShapes): Leaflet writes style options as
 * SVG attributes, which cannot read var(--*). The class is fixed when Leaflet
 * creates the path, so the key carries the band and a band change remounts it.
 */
export default function HotspotLayer({ hotspots, categoryLabel, onSelect }: Props) {
    return (
        <>
            {hotspots.map((h) => (
                <Circle
                    key={`${h.id}-${h.maxPriorityBand}`}
                    center={[h.latitude, h.longitude]}
                    radius={Math.max(h.radiusMetres + RING_PADDING_METRES, MIN_RING_METRES)}
                    pathOptions={{ className: `${styles.ring} ${BAND_CLASS[h.maxPriorityBand]}` }}
                    eventHandlers={onSelect ? { click: () => onSelect(h) } : undefined}
                >
                    {/* React children are escaped: category labels and ward names are API data. */}
                    <Tooltip sticky direction="top" className={styles.tooltip}>
                        <strong>Hotspot · {categoryLabel(h.category)}</strong>
                        <div>
                            {formatCount(h.incidentCount)} incidents · {formatCount(h.complaintCount)} reports
                        </div>
                        <div>Highest priority: {priorityLabel(h.maxPriorityBand)}</div>
                    </Tooltip>
                </Circle>
            ))}
        </>
    );
}
