import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { authService } from '@/services/auth.service';
import { useAuthStore } from '@/stores/auth.store';
import { ApiError } from '@/services/api';
import { errorMessage } from '@/lib/errors';
import { homePathForRole } from '@/lib/routes';
import AppHeader from '@/components/AppHeader';
import Button from '@/components/ui/Button';
import Field from '@/components/ui/Field';
import styles from './Auth.module.css';

/**
 * ONE sign-in for everyone. There is no citizen/officer choice here and there
 * must not be one: the role lives on the account, the backend assigns it, and
 * and homePathForRole decides where they land afterwards. Offering a visible
 * "officer login" would be redundant for staff and an invitation to everyone
 * else.
 */
export default function Login() {
    const navigate = useNavigate();
    const login = useAuthStore((state) => state.login);

    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [error, setError] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
        event.preventDefault();
        setError(null);
        setSubmitting(true);

        try {
            const { user } = await authService.login(email, password);
            login(user);
            navigate(homePathForRole(user.role), { replace: true });
        } catch (err) {
            // 401 here means "wrong email or password", not an expired session,
            // so the generic session copy would be actively misleading.
            setError(
                err instanceof ApiError && err.status === 401
                    ? 'That email and password don’t match an account.'
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
                    <h1 className={styles.title}>Sign in</h1>
                    <p className={styles.intro}>
                        Sign in to follow the reports you have filed.
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

                        <Field
                            label="Password"
                            type="password"
                            name="password"
                            autoComplete="current-password"
                            required
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                        />

                        <Button type="submit" fullWidth disabled={submitting}>
                            {submitting ? 'Signing in…' : 'Sign in'}
                        </Button>
                    </form>

                    <p className={styles.footer}>
                        No account? <Link to="/register">Create one</Link> to track your reports.
                    </p>
                </div>
            </main>
        </div>
    );
}
