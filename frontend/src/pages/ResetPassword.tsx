import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { authService } from '@/services/auth.service';
import { useAuthStore } from '@/stores/auth.store';
import { errorMessage } from '@/lib/errors';
import { PASSWORD_HELPER, passwordProblem } from '@/lib/password';
import AppHeader from '@/components/AppHeader';
import Button from '@/components/ui/Button';
import Field from '@/components/ui/Field';
import styles from './Auth.module.css';

/**
 * Sets a new password from a reset link.
 *
 * NOT wrapped in PublicRoute. Someone can plausibly be signed in on this device
 * and still be resetting — that is exactly what happens when they suspect
 * somebody else is also signed in — and PublicRoute would redirect them away
 * from the page that fixes it.
 *
 * A successful reset revokes every session server-side, so the local store is
 * cleared to match rather than left claiming a session that no longer exists.
 */
export default function ResetPassword() {
    const [searchParams] = useSearchParams();
    const navigate = useNavigate();
    const token = searchParams.get('token');

    const clearSession = useAuthStore((state) => state.logout);

    const [password, setPassword] = useState('');
    const [touched, setTouched] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [done, setDone] = useState(false);

    const problem = passwordProblem(password);
    const fieldError = touched && password.length > 0 ? problem : null;

    async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
        event.preventDefault();
        setTouched(true);
        if (problem !== null) return;

        if (token === null || token === '') {
            setError('That link is missing its token. Use the link from the email exactly as sent.');
            return;
        }

        setError(null);
        setSubmitting(true);

        try {
            await authService.resetPassword(token, password);
            // The server has already revoked every session, this browser's
            // included; drop the local copy so the UI agrees.
            clearSession();
            setDone(true);
        } catch (err) {
            setError(errorMessage(err));
        } finally {
            setSubmitting(false);
        }
    }

    if (done) {
        return (
            <div className={styles.page}>
                <AppHeader />
                <main className={styles.main}>
                    <div className={styles.panel}>
                        <h1 className={styles.title}>Password changed</h1>
                        <p className={styles.intro}>
                            You&rsquo;ve been signed out everywhere else as a precaution.
                            Sign in with your new password.
                        </p>
                        <Button type="button" fullWidth onClick={() => navigate('/login', { replace: true })}>
                            Sign in
                        </Button>
                    </div>
                </main>
            </div>
        );
    }

    return (
        <div className={styles.page}>
            <AppHeader />

            <main className={styles.main}>
                <div className={styles.panel}>
                    <h1 className={styles.title}>Choose a new password</h1>
                    <p className={styles.intro}>
                        Signing in on your other devices will need the new password.
                    </p>

                    <form className={styles.form} onSubmit={onSubmit} noValidate>
                        {error !== null && (
                            <p className={styles.formError} role="alert">
                                {error}
                            </p>
                        )}

                        <Field
                            label="New password"
                            type="password"
                            name="password"
                            autoComplete="new-password"
                            required
                            value={password}
                            error={fieldError ?? undefined}
                            helper={PASSWORD_HELPER}
                            onChange={(e) => setPassword(e.target.value)}
                            onBlur={() => setTouched(true)}
                        />

                        <Button type="submit" fullWidth disabled={submitting}>
                            {submitting ? 'Saving…' : 'Set new password'}
                        </Button>
                    </form>

                    <p className={styles.footer}>
                        Link expired? <Link to="/forgot-password">Request another</Link>
                    </p>
                </div>
            </main>
        </div>
    );
}
