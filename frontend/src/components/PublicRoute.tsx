import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth.store';
import { homePathForRole } from '../lib/routes';

interface Props {
    children: ReactNode;
}

// Keeps an already-authenticated user off the login page.
export default function PublicRoute({ children }: Props) {
    const { isAuthenticated, loading, user } = useAuthStore();

    if (loading) return null;
    if (isAuthenticated) return <Navigate to={homePathForRole(user?.role)} replace />;

    return <>{children}</>;
}