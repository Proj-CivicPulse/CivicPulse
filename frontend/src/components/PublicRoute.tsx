import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth.store';

interface Props {
    children: ReactNode;
}

// Keeps an already-authenticated user off the login page.
export default function PublicRoute({ children }: Props) {
    const { isAuthenticated, loading } = useAuthStore();

    if (loading) return null;
    if (isAuthenticated) return <Navigate to="/portal" replace />;

    return <>{children}</>;
}