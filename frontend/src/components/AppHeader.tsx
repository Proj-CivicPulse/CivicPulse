import { Link, NavLink } from 'react-router-dom';
import { APP_NAME } from '@/config/constants';
import { useAuthStore } from '@/stores/auth.store';
import Button from '@/components/ui/Button';
import UserMenu from '@/components/UserMenu';
import styles from './AppHeader.module.css';

export interface NavItem {
    label: string;
    to: string;
}

interface Props {
    nav?: readonly NavItem[];
    /** Full-bleed for the officer console, which has no centred measure. */
    wide?: boolean;
}

/**
 * 56px, one bottom rule, no shadow. Session-aware: the sign-in action becomes
 * the signed-in identity, so the header always answers "who am I here as".
 *
 * That identity is a monogram opening a menu (UserMenu), which also owns
 * sign-out. The header itself stays purely wayfinding — account actions live
 * behind one control rather than spending header width on a destructive
 * button nobody presses more than once a session.
 */
export default function AppHeader({ nav = [], wide = false }: Props) {
    const user = useAuthStore((state) => state.user);
    const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

    return (
        <header className={styles.header}>
            <div className={wide ? styles.innerWide : styles.inner}>
                <Link to="/" className={styles.wordmark}>
                    {APP_NAME}
                </Link>

                {nav.length > 0 && (
                    <nav className={styles.nav} aria-label="Primary">
                        {nav.map((item) => (
                            <NavLink
                                key={item.to}
                                to={item.to}
                                className={({ isActive }) => (isActive ? styles.linkActive : styles.link)}
                            >
                                {item.label}
                            </NavLink>
                        ))}
                    </nav>
                )}

                <div className={styles.actions}>
                    {isAuthenticated && user ? (
                        <UserMenu user={user} />
                    ) : (
                        <Button variant="secondary" size="compact" to="/login">
                            Sign in
                        </Button>
                    )}
                </div>
            </div>
        </header>
    );
}
