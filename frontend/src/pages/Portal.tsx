import { Navigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth.store';

/**
 * Role dispatcher for `/portal`.
 *
 * Login and both route guards redirect here rather than hardcoding a
 * destination, because where a signed-in user belongs depends on their
 * role. This is the single place that decides.
 */
export default function Portal() {
    const { isAuthenticated, loading, user } = useAuthStore();

    // Wait for the boot-time checkAuth() to resolve, otherwise a logged-in
    // user is bounced to /login on every refresh.
    if (loading) return null;
    if (!isAuthenticated) return <Navigate to="/login" replace />;

    return <Navigate to={user?.role === 'officer' ? '/dashboard' : '/my-complaints'} replace />;
}
