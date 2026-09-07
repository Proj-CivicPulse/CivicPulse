import styles from './Skeleton.module.css';

interface Props {
    /** Repeats the outline, for a list or table whose row count is known. */
    count?: number;
    /** Matches the geometry of what's loading. */
    variant?: 'row' | 'card' | 'text' | 'block';
    /** Overrides the height for `block`, e.g. a map's 320px. */
    height?: number;
    label?: string;
}

/**
 * 1px rule outlines matching the final content's geometry — no shimmer, no
 * pulsing. A shimmer is motion that carries no information, and this system
 * spends motion on three moments only.
 *
 * The wrapper is aria-busy with a polite live region so a screen reader
 * announces the wait once rather than reading empty boxes.
 */
export default function Skeleton({ count = 1, variant = 'row', height, label = 'Loading' }: Props) {
    return (
        <div className={styles.wrap} role="status" aria-busy="true" aria-live="polite">
            <span className="srOnly">{label}</span>
            {Array.from({ length: count }, (_, i) => (
                <div
                    key={i}
                    className={`${styles.item} ${styles[variant]}`}
                    style={height !== undefined ? { height: `${height}px` } : undefined}
                    aria-hidden="true"
                />
            ))}
        </div>
    );
}
