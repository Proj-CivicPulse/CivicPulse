import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { authService } from '@/services/auth.service';
import { useAuthStore } from '@/stores/auth.store';
import { errorMessage } from '@/lib/errors';
import AppHeader from '@/components/AppHeader';
import Button from '@/components/ui/Button';
import styles from './Auth.module.css';

/**
 * Landing page for the link in a verification email.
 *
 * NOT wrapped in PublicRoute: registration signs you in immediately, so the
 * common case is clicking this link while already authenticated. Bouncing
 * signed-in users away — which is what PublicRoute does — would make the
 * ordinary path impossible.
 *
 * The token arrives as a query parameter because a human clicks a link, but it
 * is POSTed from here rather than handled as a GET on the API. See
 * VerifyEmailRequest for why that matters: single-use tokens and link-preview
 * bots do not mix.
 */
export default function VerifyEmail() {
    const [searchParams] = useSearchParams();
    const token = searchParams.get('token');

    const user = useAuthStore((state) => state.user);
    const checkAuth = useAuthStore((state) => state.checkAuth);

    // A link with no token is knowable at render time, so it is the initial
    // state rather than something an effect discovers and corrects. Setting it
    // in the effect meant rendering "Confirming…" for one frame before
    // replacing it with an error, and cost an extra render to do it.
    const missingToken = token === null || token === '';

    const [state, setState] = useState<'working' | 'done' | 'failed'>(
        missingToken ? 'failed' : 'working'
    );
    const [error, setError] = useState<string | null>(
        missingToken
            ? 'That link is missing its token. Use the link from the email exactly as sent.'
            : null
    );

    // React 18 StrictMode mounts effects twice in development. The token is
    // single-use, so a second call would consume it and report failure for a
    // verification that actually succeeded.
    const attempted = useRef(false);

    useEffect(() => {
        // Spelled out rather than reusing `missingToken`, which is the same
        // condition: this form narrows `token` to string for the call below,
        // where a boolean derived elsewhere would leave it string | null.
        if (attempted.current || token === null || token === '') return;
        attempted.current = true;

        void (async () => {
            try {
                await authService.verifyEmail(token);
                setState('done');
                // Refresh the cached user so `emailVerified` stops being stale
                // for anyone who was already signed in.
                void checkAuth();
            } catch (err) {
                setState('failed');
                setError(errorMessage(err));
            }
        })();
    }, [token, checkAuth]);

    return (
        <div className={styles.page}>
            <AppHeader />

            <main className={styles.main}>
                <div className={styles.panel}>
                    {state === 'working' && (
                        <>
                            <h1 className={styles.title}>Confirming your email…</h1>
                            <p className={styles.intro}>This will only take a moment.</p>
                        </>
                    )}

                    {state === 'done' && (
                        <>
                            <h1 className={styles.title}>Email confirmed</h1>
                            <p className={styles.intro}>
                                Thanks — we know this address is yours.
                            </p>
                            <p className={styles.footer}>
                                {user !== null
                                    ? <Link to="/complaints">Go to your reports</Link>
                                    : <Link to="/login">Sign in</Link>}
                            </p>
                        </>
                    )}

                    {state === 'failed' && (
                        <>
                            <h1 className={styles.title}>That link didn&rsquo;t work</h1>
                            <p className={styles.formError} role="alert">{error}</p>
                            <p className={styles.intro}>
                                Verification links expire, and each one can only be used once.
                                {user !== null
                                    ? ' You can send yourself a new one.'
                                    : ' Sign in and we can send you a new one.'}
                            </p>
                            {user !== null ? <ResendButton /> : (
                                <p className={styles.footer}>
                                    <Link to="/login">Sign in</Link>
                                </p>
                            )}
                        </>
                    )}
                </div>
            </main>
        </div>
    );
}

/** Only rendered for a signed-in user — the endpoint takes the address from the session. */
function ResendButton() {
    const [sending, setSending] = useState(false);
    const [sent, setSent] = useState(false);
    const [error, setError] = useState<string | null>(null);

    async function onClick() {
        setSending(true);
        setError(null);
        try {
            await authService.resendVerification();
            setSent(true);
        } catch (err) {
            setError(errorMessage(err));
        } finally {
            setSending(false);
        }
    }

    if (sent) {
        return <p className={styles.intro}>Sent. Check your inbox for the new link.</p>;
    }

    return (
        <>
            {error !== null && <p className={styles.formError} role="alert">{error}</p>}
            <Button type="button" fullWidth disabled={sending} onClick={onClick}>
                {sending ? 'Sending…' : 'Send a new link'}
            </Button>
        </>
    );
}
