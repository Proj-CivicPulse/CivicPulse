import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuthStore, type UserRole } from '../stores/auth.store';
import { homePathForRole } from '../lib/routes';

interface Props {
    children: ReactNode;
    requireRole?: UserRole; // e.g. 'officer' for dashboard routes
}

export default function ProtectedRoute({ children, requireRole }: Props) {
    const { isAuthenticated, loading, user } = useAuthStore();

    // Wait for boot-time checkAuth() to resolve — otherwise a logged-in
    // user briefly flashes the login redirect on every page refresh.
    if (loading) return null;
    if (!isAuthenticated) return <Navigate to="/login" replace />;
    // Wrong role for this route: send them straight to the home their own role
    // owns, rather than to a shared dispatcher that would only bounce them again.
    if (requireRole && user?.role !== requireRole) {
        return <Navigate to={homePathForRole(user?.role)} replace />;
    }

    return <>{children}</>;
}