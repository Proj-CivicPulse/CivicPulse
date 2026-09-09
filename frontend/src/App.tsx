import { Suspense, lazy, useEffect, type ComponentType } from 'react';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { ErrorBoundary } from './components/ErrorBoundary';
import ErrorFallback from './pages/ErrorFallback';
import ProtectedRoute from './components/ProtectedRoute';
import PublicRoute from './components/PublicRoute';
import RouteFallback from './components/RouteFallback';
import { useAuthStore } from './stores/auth.store';
// Eagerly loaded — it's the entry point every visitor sees first, so
// code-splitting it would only add a loading flash.
import Landing from './pages/Landing';

// If a deploy ships new JS chunk hashes while a tab is still open, a stale
// lazy-loaded route fails to fetch its chunk. Reload once, automatically,
// instead of leaving the user on a blank screen.
const CHUNK_RELOAD_GUARD_KEY = 'civicpulse:chunk-reload-once';

function isChunkLoadError(error: unknown): boolean {
    const message = error instanceof Error ? error.message : String(error);
    return /Failed to fetch dynamically imported module|Importing a module script failed|ChunkLoadError/i.test(message);
}

function lazyWithChunkRecovery<T extends ComponentType<any>>(importer: () => Promise<{ default: T }>) {
    return lazy(async () => {
        try {
            const mod = await importer();
            sessionStorage.removeItem(CHUNK_RELOAD_GUARD_KEY);
            return mod;
        } catch (error) {
            if (isChunkLoadError(error) && sessionStorage.getItem(CHUNK_RELOAD_GUARD_KEY) !== '1') {
                sessionStorage.setItem(CHUNK_RELOAD_GUARD_KEY, '1');
                window.location.reload();
                return new Promise<never>(() => { }); // keep Suspense pending during reload
            }
            throw error;
        }
    });
}

const SubmitComplaint = lazyWithChunkRecovery(() => import('./pages/SubmitComplaint'));
const MyComplaints = lazyWithChunkRecovery(() => import('./pages/MyComplaints'));
const IncidentDashboard = lazyWithChunkRecovery(() => import('./pages/IncidentDashboard'));
const Login = lazyWithChunkRecovery(() => import('./pages/Login'));
const Register = lazyWithChunkRecovery(() => import('./pages/Register'));
const VerifyEmail = lazyWithChunkRecovery(() => import('./pages/VerifyEmail'));
const ForgotPassword = lazyWithChunkRecovery(() => import('./pages/ForgotPassword'));
const ResetPassword = lazyWithChunkRecovery(() => import('./pages/ResetPassword'));
const NotFound = lazyWithChunkRecovery(() => import('./pages/NotFound'));

export default function App() {
    const checkAuth = useAuthStore((state) => state.checkAuth);

    useEffect(() => {
        // Defer off the critical rendering path — auth status isn't needed
        // for first paint.
        let idleId: number | undefined;
        let timeoutId: number | undefined;
        const run = () => void checkAuth();

        if (typeof window.requestIdleCallback === 'function') {
            idleId = window.requestIdleCallback(run, { timeout: 1200 });
        } else {
            timeoutId = window.setTimeout(run, 0);
        }

        return () => {
            if (idleId !== undefined && typeof window.cancelIdleCallback === 'function') {
                window.cancelIdleCallback(idleId);
            }
            if (timeoutId !== undefined) window.clearTimeout(timeoutId);
        };
    }, [checkAuth]);

    return (
        <ErrorBoundary fallback={(error, retry) => <ErrorFallback error={error} retry={retry} />}>
            <BrowserRouter>
                <Suspense fallback={<RouteFallback />}>
                    <Routes>
                        <Route path="/" element={<Landing />} />

                        {/* Public — reporting a problem doesn't require an account.
                            Settled in docs/endpoints.md; tracking is what an
                            account buys you. */}
                        <Route path="/complaints/new" element={<SubmitComplaint />} />

                        <Route
                            path="/complaints"
                            element={
                                <ProtectedRoute requireRole="citizen">
                                    <MyComplaints />
                                </ProtectedRoute>
                            }
                        />

                        <Route
                            path="/dashboard"
                            element={
                                <ProtectedRoute requireRole="officer">
                                    <IncidentDashboard />
                                </ProtectedRoute>
                            }
                        />

                        <Route
                            path="/login"
                            element={
                                <PublicRoute>
                                    <Login />
                                </PublicRoute>
                            }
                        />

                        <Route
                            path="/register"
                            element={
                                <PublicRoute>
                                    <Register />
                                </PublicRoute>
                            }
                        />

                        {/* Landing pages for emailed links. Deliberately NOT
                            wrapped in PublicRoute: registration signs you in
                            immediately, so verifying while already
                            authenticated is the normal case, and someone
                            resetting a password may well be signed in on this
                            device already. PublicRoute would redirect both
                            away from the page they were sent to. */}
                        <Route path="/verify-email" element={<VerifyEmail />} />
                        <Route path="/reset-password" element={<ResetPassword />} />

                        <Route
                            path="/forgot-password"
                            element={
                                <PublicRoute>
                                    <ForgotPassword />
                                </PublicRoute>
                            }
                        />

                        <Route path="*" element={<NotFound />} />
                    </Routes>
                </Suspense>
            </BrowserRouter>
        </ErrorBoundary>
    );
}
