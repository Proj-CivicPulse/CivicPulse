import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { authService } from '@/services/auth.service';
import { useAuthStore } from '@/stores/auth.store';
import { ApiError } from '@/services/api';
import { errorMessage } from '@/lib/errors';
import { PASSWORD_HELPER, passwordProblem } from '@/lib/password';
import { homePathForRole } from '@/lib/routes';
import AppHeader from '@/components/AppHeader';
import Button from '@/components/ui/Button';
import Field from '@/components/ui/Field';
import styles from './Auth.module.css';

/**
 * Registration always creates a citizen — officer accounts are provisioned
 * deliberately, by hand, so there is no role choice to offer here.
 *
 * The password rule lives in lib/password.ts, shared with the reset page and
 * mirroring RegisterRequest's @Pattern.
 */
export default function Register() {
    const navigate = useNavigate();
    const login = useAuthStore((state) => state.login);

    const [name, setName] = useState('');
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [touchedPassword, setTouchedPassword] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    const pwProblem = passwordProblem(password);
    // Only nag after the field has been left once — validating on the first
    // keystroke shows an error before anyone has had a chance to be wrong.
    const pwError = touchedPassword && password.length > 0 ? pwProblem : null;

    async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
        event.preventDefault();
        setTouchedPassword(true);
        if (pwProblem !== null) return;

        setError(null);
        setSubmitting(true);

        try {
            const { user } = await authService.register(name, email, password);
            login(user);
            // Always a citizen today, but routed by role rather than hardcoded
            // so this keeps working if registration ever creates anything else.
            navigate(homePathForRole(user.role), { replace: true });
        } catch (err) {
            setError(
                err instanceof ApiError && err.status === 409
                    ? 'An account with that email already exists. Sign in instead.'
                    : errorMessage(err)
            );
        } finally {
            setSubmitting(false);
        }
    }

    return (
        <div className={styles.page}>
            <AppHeader />

            <main className={styles.main}>
                <div className={styles.panel}>
                    <h1 className={styles.title}>Create an account</h1>
                    <p className={styles.intro}>
                        You don&rsquo;t need one to report a problem — an account is what lets you
                        track what you filed.
                    </p>

                    <form className={styles.form} onSubmit={onSubmit} noValidate>
                        {error !== null && (
                            <p className={styles.formError} role="alert">
                                {error}
                            </p>
                        )}

                        <Field
                            label="Name"
                            name="name"
                            autoComplete="name"
                            required
                            value={name}
                            onChange={(e) => setName(e.target.value)}
                        />

                        <Field
                            label="Email"
                            type="email"
                            name="email"
                            autoComplete="email"
                            required
                            value={email}
                            onChange={(e) => setEmail(e.target.value)}
                        />

                        <Field
                            label="Password"
                            type="password"
                            name="password"
                            autoComplete="new-password"
                            required
                            value={password}
                            error={pwError ?? undefined}
                            helper={PASSWORD_HELPER}
                            onChange={(e) => setPassword(e.target.value)}
                            onBlur={() => setTouchedPassword(true)}
                        />

                        <Button type="submit" fullWidth disabled={submitting}>
                            {submitting ? 'Creating account…' : 'Create account'}
                        </Button>
                    </form>

                    <p className={styles.footer}>
                        Already have an account? <Link to="/login">Sign in</Link>
                    </p>
                </div>
            </main>
        </div>
    );
}
