import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { formatCount } from '@/lib/format';
import styles from './TrendChart.module.css';

export interface TrendPoint {
    start: string;
    count: number;
}

interface Props {
    points: readonly TrendPoint[];
    bucket: 'day' | 'week';
    /** Accessible name, e.g. "Complaints reported per day". */
    label: string;
    /** Chart, or its table twin — the parent owns the toggle. */
    view?: 'chart' | 'table';
}

const HEIGHT = 168;
const PAD = { top: 12, right: 8, bottom: 24, left: 36 };
const MAX_BAR = 24;

const dayFormat = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', timeZone: 'UTC' });

function bucketLabel(start: string, bucket: 'day' | 'week'): string {
    const date = new Date(start);
    return bucket === 'week' ? `Week of ${dayFormat.format(date)}` : dayFormat.format(date);
}

/** 3–4 round ticks from 0, so the axis reads 0 / 5 / 10 rather than 0 / 3.7 / 7.4. */
function niceTicks(max: number): number[] {
    if (max <= 0) return [0, 1];
    const rough = max / 3;
    const pow = 10 ** Math.floor(Math.log10(rough));
    const step = [1, 2, 5, 10].map((m) => m * pow).find((s) => s >= rough) ?? pow * 10;
    const top = Math.ceil(max / step) * step;
    const ticks: number[] = [];
    for (let v = 0; v <= top + step / 2; v += step) ticks.push(Math.round(v));
    return ticks;
}

/**
 * Complaint counts over time: one series, so one colour (the seal) and no
 * legend box — the panel heading names it.
 *
 * Marks follow the dataviz spec: columns capped at 24px, a 2px surface gap
 * between neighbours, 4px rounded data-end and square at the baseline, solid
 * hairline grid. Every bucket is a column, zeros included — the server sends
 * them, so a gap in reporting is visible rather than interpolated over.
 *
 * Hover finds the X: each bucket's whole band is its hit area, and the readout
 * leads with the value. The same readout follows the keyboard (arrow keys on
 * the focused chart), and the table view carries every value without hover.
 */
export default function TrendChart({ points, bucket, label, view = 'chart' }: Props) {
    const wrapRef = useRef<HTMLDivElement>(null);
    const [width, setWidth] = useState(600);
    const [active, setActive] = useState<number | null>(null);

    useEffect(() => {
        const el = wrapRef.current;
        if (!el) return;
        const observer = new ResizeObserver(([entry]) => {
            if (entry) setWidth(Math.max(240, Math.floor(entry.contentRect.width)));
        });
        observer.observe(el);
        return () => observer.disconnect();
    }, []);

    const total = useMemo(() => points.reduce((sum, p) => sum + p.count, 0), [points]);
    const max = useMemo(() => Math.max(0, ...points.map((p) => p.count)), [points]);
    const ticks = useMemo(() => niceTicks(max), [max]);
    const top = ticks[ticks.length - 1] ?? 1;

    if (view === 'table') {
        return (
            <div className={styles.tableWrap}>
                <table className={styles.table}>
                    <caption className="srOnly">{label}</caption>
                    <thead>
                        <tr>
                            <th scope="col">{bucket === 'week' ? 'Week starting' : 'Day'}</th>
                            <th scope="col" className={styles.num}>
                                Complaints
                            </th>
                        </tr>
                    </thead>
                    <tbody>
                        {points.map((p) => (
                            <tr key={p.start}>
                                <td>{dayFormat.format(new Date(p.start))}</td>
                                <td className={styles.num}>{formatCount(p.count)}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        );
    }

    const plotW = width - PAD.left - PAD.right;
    const plotH = HEIGHT - PAD.top - PAD.bottom;
    const band = points.length > 0 ? plotW / points.length : plotW;
    const barW = Math.max(1, Math.min(MAX_BAR, band - 2));
    const y = (v: number) => PAD.top + plotH - (v / top) * plotH;
    const radius = Math.min(4, barW / 2);

    // Selective x labels: first, middle, last — never one per column.
    const labelled = new Set([0, Math.floor((points.length - 1) / 2), points.length - 1]);

    const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
        if (points.length === 0) return;
        if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
            event.preventDefault();
            const delta = event.key === 'ArrowRight' ? 1 : -1;
            setActive((current) =>
                Math.min(points.length - 1, Math.max(0, (current ?? (delta > 0 ? -1 : points.length)) + delta))
            );
        } else if (event.key === 'Escape') {
            setActive(null);
        }
    };

    const activePoint = active !== null ? points[active] : undefined;
    const tooltipLeft = active !== null ? PAD.left + band * active + band / 2 : 0;

    return (
        <div
            ref={wrapRef}
            className={styles.wrap}
            tabIndex={0}
            role="img"
            aria-label={`${label}: ${formatCount(total)} in total. Use the arrow keys to read each ${bucket}.`}
            onKeyDown={onKeyDown}
            onBlur={() => setActive(null)}
            onPointerLeave={() => setActive(null)}
        >
            <svg width={width} height={HEIGHT} className={styles.svg} aria-hidden="true">
                {ticks.map((t) => (
                    <g key={t}>
                        <line x1={PAD.left} x2={width - PAD.right} y1={y(t)} y2={y(t)} className={styles.grid} />
                        <text x={PAD.left - 6} y={y(t)} className={styles.tick} textAnchor="end" dominantBaseline="middle">
                            {formatCount(t)}
                        </text>
                    </g>
                ))}

                {points.map((p, i) => {
                    const x = PAD.left + band * i + (band - barW) / 2;
                    const h = y(0) - y(p.count);
                    if (h <= 0) return null;
                    const r = Math.min(radius, h);
                    // Rounded data-end, square at the baseline.
                    const d = `M${x},${y(0)} V${y(p.count) + r} Q${x},${y(p.count)} ${x + r},${y(p.count)} H${x + barW - r} Q${x + barW},${y(p.count)} ${x + barW},${y(p.count) + r} V${y(0)} Z`;
                    return <path key={p.start} d={d} className={i === active ? styles.barActive : styles.bar} />;
                })}

                {active !== null && (
                    <line
                        x1={tooltipLeft}
                        x2={tooltipLeft}
                        y1={PAD.top}
                        y2={y(0)}
                        className={styles.crosshair}
                    />
                )}

                {points.map((p, i) =>
                    labelled.has(i) ? (
                        <text
                            key={`x-${p.start}`}
                            x={PAD.left + band * i + band / 2}
                            y={HEIGHT - 6}
                            className={styles.tick}
                            textAnchor={i === 0 ? 'start' : i === points.length - 1 ? 'end' : 'middle'}
                        >
                            {dayFormat.format(new Date(p.start))}
                        </text>
                    ) : null
                )}

                {/* Hit areas: the whole band, bigger than any column. */}
                {points.map((p, i) => (
                    <rect
                        key={`hit-${p.start}`}
                        x={PAD.left + band * i}
                        y={PAD.top}
                        width={band}
                        height={plotH}
                        className={styles.hit}
                        onPointerMove={() => setActive(i)}
                    />
                ))}
            </svg>

            {activePoint && (
                <div
                    className={styles.tooltip}
                    style={{
                        left: Math.min(Math.max(tooltipLeft, 70), width - 70),
                    }}
                    aria-live="polite"
                >
                    <strong className={styles.tooltipValue}>{formatCount(activePoint.count)}</strong>
                    <span className={styles.tooltipLabel}>{bucketLabel(activePoint.start, bucket)}</span>
                </div>
            )}
        </div>
    );
}

/**
 * A row-sized trend for tables: columns only, no axes. De-emphasis grey with
 * the latest bucket in the seal, so the eye lands on "now".
 */
export function Sparkline({ points, label }: { points: readonly TrendPoint[]; label: string }) {
    const w = 96;
    const h = 22;
    const max = Math.max(1, ...points.map((p) => p.count));
    const band = points.length > 0 ? w / points.length : w;
    const barW = Math.max(1, band - 1);
    const total = points.reduce((sum, p) => sum + p.count, 0);
    return (
        <svg width={w} height={h} role="img" aria-label={`${label}: ${formatCount(total)} in the period`}>
            {points.map((p, i) => {
                const bh = (p.count / max) * (h - 2);
                return bh > 0 ? (
                    <rect
                        key={p.start}
                        x={i * band}
                        y={h - bh}
                        width={barW}
                        height={bh}
                        className={i === points.length - 1 ? styles.sparkNow : styles.spark}
                    />
                ) : (
                    <rect key={p.start} x={i * band} y={h - 1} width={barW} height={1} className={styles.sparkZero} />
                );
            })}
        </svg>
    );
}
