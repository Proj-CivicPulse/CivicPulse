import { useEffect, useId, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { ChevronDown, FileText, LayoutDashboard, LogOut } from 'lucide-react';
import { useAuthStore, type AuthUser, type UserRole } from '@/stores/auth.store';
import styles from './UserMenu.module.css';

interface Props {
    user: AuthUser;
}

const ROLE_LABEL: Record<UserRole, string> = {
    citizen: 'Citizen',
    officer: 'Ward officer',
};

/**
 * Initials for the monogram.
 *
 * `Array.from` rather than `name[0]`, so a name beginning with an accented
 * grapheme or anything outside the BMP is not sliced mid-character into a
 * replacement glyph. One letter for a single-word name, first + last for
 * anything longer — two letters from one word ("AS" for "Asha") reads as an
 * abbreviation of something else.
 */
function initials(name: string): string {
    const parts = name.trim().split(/\s+/).filter(Boolean);

    // Indexed access is `string | undefined` under noUncheckedIndexedAccess,
    // so every read is defaulted rather than asserted — a display name is user
    // input and this renders on every authenticated page.
    const first = Array.from(parts[0] ?? '')[0] ?? '';
    if (first === '') return '?';
    if (parts.length === 1) return first.toUpperCase();

    const last = Array.from(parts[parts.length - 1] ?? '')[0] ?? '';
    return (first + last).toUpperCase();
}

/**
 * The signed-in identity, as a monogram that opens a menu.
 *
 * REPLACES a bare name label sitting next to a "Sign out" button. That layout
 * gave a destructive, once-a-session action the same visual weight as primary
 * navigation, and it grew the header every time an account-level action was
 * added. Collapsing it behind one control is what every mature product settles
 * on — GitHub, Linear, Vercel, Notion — because the header's job is wayfinding,
 * and account actions are a different job.
 *
 * It also RECOVERS information on small screens. The old header hid the name
 * outright below 640px, so a phone could not answer "who am I signed in as".
 * The monogram survives at any width and the panel carries the full name and
 * role, so the answer is always one tap away.
 *
 * Implemented as a real menu (roving focus, Escape, click-outside) rather than
 * a hover popover: hover has no equivalent on touch, and this holds sign-out.
 */
export default function UserMenu({ user }: Props) {
    const logout = useAuthStore((state) => state.logout);
    const location = useLocation();

    const [open, setOpen] = useState(false);

    const wrapRef = useRef<HTMLDivElement>(null);
    const triggerRef = useRef<HTMLButtonElement>(null);
    const itemRefs = useRef<Array<HTMLElement | null>>([]);

    // Set when the menu is opened from the keyboard. A pointer user's focus
    // should stay put; a keyboard user has nowhere to go unless we move it.
    const focusFirstOnOpen = useRef(false);

    const menuId = useId();

    function closeMenu(returnFocus: boolean) {
        setOpen(false);
        if (returnFocus) {
            triggerRef.current?.focus();
        }
    }

    useEffect(() => {
        if (!open) return;

        if (focusFirstOnOpen.current) {
            focusFirstOnOpen.current = false;
            itemRefs.current[0]?.focus();
        }

        // `pointerdown`, not `click`: a click listener fires after the browser
        // has already moved focus, which on a native control reads as the menu
        // closing a beat late.
        function onPointerDown(event: PointerEvent) {
            if (!wrapRef.current?.contains(event.target as Node)) {
                setOpen(false);
            }
        }

        document.addEventListener('pointerdown', onPointerDown);
        return () => document.removeEventListener('pointerdown', onPointerDown);
    }, [open]);

    // A menu that survives navigation would hang over the page the user just
    // asked for — including navigation this menu did not initiate, such as the
    // browser back button.
    //
    // Adjusted during render rather than in an effect. React re-runs the
    // component immediately without committing the stale value, so the panel
    // never paints over the new route; an effect would close it one frame late.
    // This is the documented pattern for deriving state from a changing prop.
    const [lastPath, setLastPath] = useState(location.pathname);
    if (lastPath !== location.pathname) {
        setLastPath(location.pathname);
        setOpen(false);
    }

    function onSignOut() {
        logout();
        // A full document navigation, deliberately, not navigate('/').
        //
        // Two reasons. A client-side navigation races ProtectedRoute: clearing
        // the session re-renders the page you are still on, its guard redirects
        // to /login, and that redirect wins — so signing out dropped you on a
        // sign-in form instead of the front door. And a reload is what actually
        // empties the React Query cache; without it the previous session's
        // reports sit in memory for gcTime and are handed straight to whoever
        // signs in next on this browser.
        window.location.assign('/');
    }

    /** Roving focus between items, per the WAI-ARIA menu pattern. */
    function onMenuKeyDown(event: React.KeyboardEvent) {
        const items = itemRefs.current.filter((el): el is HTMLElement => el !== null);
        if (items.length === 0) return;

        const currentIndex = items.indexOf(document.activeElement as HTMLElement);

        switch (event.key) {
            case 'ArrowDown':
                event.preventDefault();
                items[(currentIndex + 1) % items.length]?.focus();
                break;
            case 'ArrowUp':
                event.preventDefault();
                items[(currentIndex - 1 + items.length) % items.length]?.focus();
                break;
            case 'Home':
                event.preventDefault();
                items[0]?.focus();
                break;
            case 'End':
                event.preventDefault();
                items[items.length - 1]?.focus();
                break;
            case 'Escape':
                event.preventDefault();
                closeMenu(true);
                break;
            case 'Tab':
                // Let focus leave naturally, but do not leave the panel open
                // behind it.
                setOpen(false);
                break;
            default:
                break;
        }
    }

    function onTriggerKeyDown(event: React.KeyboardEvent) {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            focusFirstOnOpen.current = true;
            setOpen(true);
        }
    }

    const homeLink =
        user.role === 'officer'
            ? { to: '/dashboard', label: 'Ward console', Icon: LayoutDashboard }
            : { to: '/complaints', label: 'My reports', Icon: FileText };

    return (
        <div className={styles.wrap} ref={wrapRef}>
            <button
                type="button"
                ref={triggerRef}
                className={styles.trigger}
                aria-haspopup="menu"
                aria-expanded={open}
                aria-controls={open ? menuId : undefined}
                onClick={() => setOpen((wasOpen) => !wasOpen)}
                onKeyDown={onTriggerKeyDown}
            >
                <span className={styles.monogram} aria-hidden="true">
                    {initials(user.name)}
                </span>
                {/* The visible name is decorative here — the button's accessible
                    name comes from the label below, which stays complete even
                    when the text is hidden at narrow widths. */}
                <span className={styles.triggerName} aria-hidden="true">
                    {user.name}
                </span>
                <ChevronDown
                    className={open ? styles.chevronOpen : styles.chevron}
                    size={14}
                    strokeWidth={2}
                    aria-hidden="true"
                />
                <span className={styles.srOnly}>
                    {`Account menu for ${user.name}`}
                </span>
            </button>

            {open && (
                <div
                    id={menuId}
                    role="menu"
                    aria-label="Account"
                    className={styles.menu}
                    onKeyDown={onMenuKeyDown}
                >
                    <div className={styles.identity}>
                        <span className={styles.identityName}>{user.name}</span>
                        <span className={styles.identityRole}>{ROLE_LABEL[user.role]}</span>
                    </div>

                    <div className={styles.divider} role="none" />

                    <Link
                        to={homeLink.to}
                        role="menuitem"
                        className={styles.item}
                        ref={(el) => {
                            itemRefs.current[0] = el;
                        }}
                        // Covers the one case the pathname check above cannot:
                        // choosing the page you are already on, where the route
                        // never changes and the menu would otherwise stay open.
                        onClick={() => setOpen(false)}
                    >
                        <homeLink.Icon size={15} strokeWidth={1.75} aria-hidden="true" />
                        {homeLink.label}
                    </Link>

                    <div className={styles.divider} role="none" />

                    <button
                        type="button"
                        role="menuitem"
                        className={styles.itemSignOut}
                        ref={(el) => {
                            itemRefs.current[1] = el;
                        }}
                        onClick={onSignOut}
                    >
                        <LogOut size={15} strokeWidth={1.75} aria-hidden="true" />
                        Sign out
                    </button>
                </div>
            )}
        </div>
    );
}
