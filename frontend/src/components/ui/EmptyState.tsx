import type { ReactNode } from 'react';
import styles from './EmptyState.module.css';

interface Props {
    /** One line. Empty states are invitations, not apologies. */
    message: string;
    /** Optional second line, for an error's "what to do next". */
    detail?: string;
    action?: ReactNode;
    /** Renders the message in the error colour without changing the layout. */
    tone?: 'neutral' | 'error';
}

/**
 * No illustration, no oversized icon. A missing list is a fact to state and a
 * next step to offer, not an occasion for artwork.
 */
export default function EmptyState({ message, detail, action, tone = 'neutral' }: Props) {
    return (
        <div className={styles.wrap} role={tone === 'error' ? 'alert' : undefined}>
            <p className={tone === 'error' ? styles.messageError : styles.message}>{message}</p>
            {detail !== undefined && <p className={styles.detail}>{detail}</p>}
            {action !== undefined && <div className={styles.action}>{action}</div>}
        </div>
    );
}
