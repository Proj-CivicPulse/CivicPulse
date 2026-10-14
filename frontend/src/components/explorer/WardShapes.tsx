import { useEffect, useMemo } from 'react';
import { GeoJSON, useMap } from 'react-leaflet';
import L from 'leaflet';
import type { Feature, FeatureCollection } from 'geojson';
import type { WardBoundaries } from '@/services/ward.service';
import styles from './WardShapes.module.css';

/**
 * Density bins for the choropleth. Fixed thresholds rather than quantiles, so
 * a colour means the same count on every visit — a quantile legend redraws
 * itself whenever the data moves and a ward can change colour without
 * changing at all. Zero is its own class with no fill.
 */
export const DENSITY_BINS: readonly { min: number; max: number | null; label: string }[] = [
    { min: 1, max: 1, label: '1' },
    { min: 2, max: 2, label: '2' },
    { min: 3, max: 4, label: '3–4' },
    { min: 5, max: 9, label: '5–9' },
    { min: 10, max: null, label: '10+' },
];

const DENSITY_CLASS = [
    styles.d0,
    styles.d1,
    styles.d2,
    styles.d3,
    styles.d4,
    styles.d5,
] as const;

export function densityClass(count: number): string {
    if (count <= 0) return DENSITY_CLASS[0] ?? '';
    const index = DENSITY_BINS.findIndex((bin) => bin.max === null || count <= bin.max);
    return DENSITY_CLASS[index + 1] ?? '';
}

interface Props {
    boundaries: WardBoundaries;
    /** wardId -> the count to shade by. Absent means zero. */
    counts?: ReadonlyMap<string, number>;
    /** What the count is, for the hover text: "active incidents". */
    countLabel?: string;
    /** Outline one ward and fit the map to it (ward detail). */
    highlightWardId?: string | null;
    /** Fit the map to every ward (the city view). Ignored when a ward is highlighted. */
    fitAll?: boolean;
    onSelect?: (wardId: string) => void;
}

/**
 * Ward polygons as a layer inside MapView.
 *
 * Colours are CSS classes, never Leaflet style values: Leaflet writes style
 * options as SVG attributes, which cannot read var(--*), and tokens.css is the
 * only place a colour may be written. A class rule beats a presentation
 * attribute, so the tokens win.
 *
 * Hover text is built with textContent — ward names are API data, and
 * bindTooltip with a string would treat them as HTML.
 */
export default function WardShapes({
    boundaries,
    counts,
    countLabel,
    highlightWardId = null,
    fitAll = false,
    onSelect,
}: Props) {
    // GeoJSON layers are immutable in react-leaflet: a new key is how new
    // counts reach the polygons. The key changes only when a count does.
    const layerKey = useMemo(() => {
        if (!counts) return `plain-${highlightWardId ?? ''}`;
        let hash = 0;
        for (const [id, n] of counts) hash = (hash * 31 + Number(id) * 7 + n) | 0;
        return `counts-${hash}-${highlightWardId ?? ''}`;
    }, [counts, highlightWardId]);

    return (
        <>
            <GeoJSON
                key={layerKey}
                data={boundaries as unknown as FeatureCollection}
                style={(feature) => {
                    const id = String(feature?.properties?.id ?? '');
                    const isHighlighted = id === highlightWardId;
                    const shade = counts ? densityClass(counts.get(id) ?? 0) : styles.outline;
                    return {
                        className: [styles.ward, shade, isHighlighted ? styles.highlight : '']
                            .filter(Boolean)
                            .join(' '),
                    };
                }}
                onEachFeature={(feature, layer) => {
                    const id = String(feature.properties?.id ?? '');
                    const tip = document.createElement('div');
                    const name = document.createElement('strong');
                    name.textContent = `${feature.properties?.code ?? ''} · ${feature.properties?.name ?? ''}`;
                    tip.appendChild(name);
                    if (counts && countLabel) {
                        const line = document.createElement('div');
                        line.textContent = `${counts.get(id) ?? 0} ${countLabel}`;
                        tip.appendChild(line);
                    }
                    layer.bindTooltip(tip, { sticky: true, direction: 'top', className: styles.tooltip });
                    if (onSelect) layer.on('click', () => onSelect(id));
                }}
            />
            {highlightWardId ? (
                <FitWard boundaries={boundaries} wardId={highlightWardId} />
            ) : fitAll ? (
                <FitCity boundaries={boundaries} />
            ) : null}
        </>
    );
}

/** Frames one ward once its geometry is known. */
function FitWard({ boundaries, wardId }: { boundaries: WardBoundaries; wardId: string }) {
    const map = useMap();
    useEffect(() => {
        const feature = boundaries.features.find((f) => f.properties.id === wardId);
        if (!feature) return;
        const bounds = L.geoJSON(feature as unknown as Feature).getBounds();
        if (bounds.isValid()) map.fitBounds(bounds, { padding: [24, 24] });
    }, [map, boundaries, wardId]);
    return null;
}

/** Frames the whole city once the boundaries arrive. */
function FitCity({ boundaries }: { boundaries: WardBoundaries }) {
    const map = useMap();
    useEffect(() => {
        const bounds = L.geoJSON(boundaries as unknown as FeatureCollection).getBounds();
        if (bounds.isValid()) map.fitBounds(bounds, { padding: [8, 8] });
    }, [map, boundaries]);
    return null;
}

/** The scale legend: what each shade counts. Zero shown as its own swatch. */
export function DensityLegend({ title }: { title: string }) {
    return (
        <div className={styles.legend}>
            <span className={styles.legendTitle}>{title}</span>
            <ul className={styles.legendSteps}>
                <li className={styles.legendStep}>
                    <span className={`${styles.swatch} ${styles.swatchNone}`} aria-hidden="true" />
                    0
                </li>
                {DENSITY_BINS.map((bin, i) => (
                    <li key={bin.label} className={styles.legendStep}>
                        <span className={`${styles.swatch} ${DENSITY_CLASS[i + 1] ?? ''}`} aria-hidden="true" />
                        {bin.label}
                    </li>
                ))}
            </ul>
        </div>
    );
}
