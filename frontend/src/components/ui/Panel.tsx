import type { ReactNode } from 'react';
import styles from './Panel.module.css';

interface Props {
    /** Rendered in the heading row. Omit for a plain bordered surface. */
    heading?: ReactNode;
    /** Right-aligned in the heading row — a filter, a count, an action. */
    aside?: ReactNode;
    children: ReactNode;
    /** Removes body padding, for panels whose child is a full-bleed table or map. */
    flush?: boolean;
    className?: string;
    /** Set when the panel is a labelled region, so the heading names it. */
    as?: 'div' | 'section';
    id?: string;
}

/**
 * Structure comes from a 1px rule, not a shadow. Cards do not have shadows in
 * this system; --shadow-overlay exists only for things that genuinely float
 * (modals, dropdowns, map popovers).
 */
export default function Panel({
    heading,
    aside,
    children,
    flush = false,
    className,
    as: Tag = 'div',
    id,
}: Props) {
    const classes = [styles.panel, className ?? ''].filter(Boolean).join(' ');

    return (
        <Tag className={classes} id={id}>
            {heading !== undefined && (
                <div className={styles.head}>
                    <h2 className={styles.heading}>{heading}</h2>
                    {aside !== undefined && <div className={styles.aside}>{aside}</div>}
                </div>
            )}
            <div className={flush ? styles.bodyFlush : styles.body}>{children}</div>
        </Tag>
    );
}
