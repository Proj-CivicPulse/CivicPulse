import { useState } from 'react';
import { Link } from 'react-router-dom';
import { authService } from '@/services/auth.service';
import { ApiError } from '@/services/api';
import { errorMessage } from '@/lib/errors';
import AppHeader from '@/components/AppHeader';
import Button from '@/components/ui/Button';
import Field from '@/components/ui/Field';
import styles from './Auth.module.css';

/**
 * Requests a password-reset link.
 *
 * The confirmation deliberately says "if that address has an account" rather
 * than "sent". The server answers 204 either way — it will not reveal whether
 * an address is registered — so copy that claimed a message had been sent would
 * be a lie half the time, and copy that reported "no such account" would undo
 * the very protection the endpoint is built around.
 */
export default function ForgotPassword() {
    const [email, setEmail] = useState('');
    const [submitted, setSubmitted] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
        event.preventDefault();
        setError(null);
        setSubmitting(true);

        try {
            await authService.forgotPassword(email);
            setSubmitted(true);
        } catch (err) {
            // 429 is the anti-bombing budget; its message names the wait, so
            // prefer it over the generic copy in lib/errors.ts.
            setError(
                err instanceof ApiError && err.status === 429
                    ? err.message
                    : errorMessage(err)
            );
        } finally {
            setSubmitting(false);
        }
    }

    if (submitted) {
        return (
            <div className={styles.page}>
                <AppHeader />
                <main className={styles.main}>
                    <div className={styles.panel}>
                        <h1 className={styles.title}>Check your email</h1>
                        <p className={styles.intro}>
                            If <strong>{email}</strong> has a CivicPulse account, a reset link
                            is on its way. It expires in an hour and can only be used once.
                        </p>
                        <p className={styles.footer}>
                            <Link to="/login">Back to sign in</Link>
                        </p>
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
                    <h1 className={styles.title}>Reset your password</h1>
                    <p className={styles.intro}>
                        Enter the email you signed up with and we&rsquo;ll send you a link
                        to choose a new password.
                    </p>

                    <form className={styles.form} onSubmit={onSubmit} noValidate>
                        {error !== null && (
                            <p className={styles.formError} role="alert">
                                {error}
                            </p>
                        )}

                        <Field
                            label="Email"
                            type="email"
                            name="email"
                            autoComplete="email"
                            required
                            value={email}
                            onChange={(e) => setEmail(e.target.value)}
                        />

                        <Button type="submit" fullWidth disabled={submitting}>
                            {submitting ? 'Sending…' : 'Send reset link'}
                        </Button>
                    </form>

                    <p className={styles.footer}>
                        Remembered it? <Link to="/login">Sign in</Link>
                    </p>
                </div>
            </main>
        </div>
    );
}
