import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth.store';
import { authService } from '../services/auth.service';
import { ApiError } from '../services/api';

export default function Login() {
    const login = useAuthStore((state) => state.login);
    const navigate = useNavigate();

    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [error, setError] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    // React.SubmitEvent is the current (non-deprecated) type for form
    // submit handlers as of @types/react 19.2.10+. No import needed — the
    // React namespace is available globally via @types/react.
    async function handleSubmit(e: React.SubmitEvent<HTMLFormElement>) {
        e.preventDefault();
        setError(null);
        setSubmitting(true);
        try {
            const { user } = await authService.login(email, password);
            login(user);
            navigate('/portal', { replace: true });
        } catch (err) {
            setError(err instanceof ApiError ? err.message : 'Login failed. Please check your credentials.');
        } finally {
            setSubmitting(false);
        }
    }

    return (
        <form onSubmit={handleSubmit}>
            <h1>Sign in</h1>
            {error && <p role="alert">{error}</p>}
            <label htmlFor="email">Email</label>
            <input
                id="email"
                type="email"
                required
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
            />
            <label htmlFor="password">Password</label>
            <input
                id="password"
                type="password"
                required
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
            />
            <button type="submit" disabled={submitting}>
                {submitting ? 'Signing in…' : 'Sign in'}
            </button>
        </form>
    );
}