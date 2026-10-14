import type { ReactNode } from 'react';
import { formatCount } from '@/lib/format';
import styles from './Figures.module.css';

/** A bar's colour role. Named tones map to tokens in the stylesheet only. */
export type BarTone = 'seal' | 'low' | 'medium' | 'high' | 'critical';

export interface BarDatum {
    key: string;
    label: string;
    value: number;
    tone?: BarTone;
}

/**
 * Horizontal labelled bars — few rows, so every value is printed and no hover
 * or table twin is needed: the figure already is its own table.
 *
 * Nominal rows (categories) take one tone, the seal; ordinal rows (priority
 * bands) take the priority ramp, because there the colour carries the order.
 * Labels and values stay in ink — never in the bar's colour.
 */
export function DistributionBars({ data, label }: { data: readonly BarDatum[]; label: string }) {
    const max = Math.max(1, ...data.map((d) => d.value));
    return (
        <ul className={styles.bars} aria-label={label}>
            {data.map((d) => (
                <li key={d.key} className={styles.barRow}>
                    <span className={styles.barLabel}>{d.label}</span>
                    <span className={styles.track} aria-hidden="true">
                        {d.value > 0 && (
                            <span
                                className={`${styles.bar} ${styles[d.tone ?? 'seal'] ?? ''}`}
                                style={{ width: `${(d.value / max) * 100}%` }}
                            />
                        )}
                    </span>
                    <span className={styles.barValue}>{formatCount(d.value)}</span>
                </li>
            ))}
        </ul>
    );
}

export interface Tile {
    label: string;
    value: ReactNode;
    /** One quiet line under the value: a unit, a period, a caveat. */
    note?: string;
}

/**
 * A KPI row. Values are proportional figures in the reading face — never the
 * display serif, never tabular at this size.
 */
export function StatTiles({ tiles }: { tiles: readonly Tile[] }) {
    return (
        <dl className={styles.tiles}>
            {tiles.map((tile) => (
                <div key={tile.label} className={styles.tile}>
                    <dt className={styles.tileLabel}>{tile.label}</dt>
                    <dd className={styles.tileValue}>{tile.value}</dd>
                    {tile.note && <dd className={styles.tileNote}>{tile.note}</dd>}
                </div>
            ))}
        </dl>
    );
}
