import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import styles from './CTAButton.module.css';

interface Props {
    to: string;
    variant?: 'primary' | 'secondary';
    children: ReactNode;
}

// Shared call-to-action link, reused by Hero, Audiences, and CTASection so
// button styling lives in exactly one place.
export default function CTAButton({ to, variant = 'primary', children }: Props) {
    return (
        <Link to={to} className={`${styles.btn} ${styles[variant]}`}>
            {children}
        </Link>
    );
}
