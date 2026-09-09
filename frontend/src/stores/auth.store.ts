import { create } from 'zustand';
import { authService } from '../services/auth.service';
import { ApiError } from '../services/api';

export type UserRole = 'citizen' | 'officer';

export interface AuthUser {
    id: string;
    name: string;
    role: UserRole;
    /**
     * Whether the address has been confirmed. Recorded, not enforced — the
     * server gates nothing on it (see EmailVerificationService), so this is
     * here for the UI to nudge with, not to lock anything.
     */
    emailVerified: boolean;
}

interface AuthState {
    user: AuthUser | null;
    isAuthenticated: boolean;
    loading: boolean; // true while the boot-time checkAuth() call is resolving
    login: (user: AuthUser) => void;
    logout: () => void;
    checkAuth: () => Promise<void>;
}

// No `persist` middleware on purpose — don't cache auth state/tokens in
// localStorage (readable by any injected script if you ever have an XSS
// bug). Session state is re-derived on boot via checkAuth(), backed by
// Spring's httpOnly cookie.
export const useAuthStore = create<AuthState>((set) => ({
    user: null,
    isAuthenticated: false,
    loading: true,

    login: (user) => set({ user, isAuthenticated: true, loading: false }),

    logout: () => {
        set({ user: null, isAuthenticated: false, loading: false });
        void authService.logout().catch(() => {
            // Already logged out client-side regardless of the server response.
        });
    },

    checkAuth: async () => {
        try {
            const user = await authService.getMe();
            set({ user, isAuthenticated: true, loading: false });
        } catch (err) {
            if (err instanceof ApiError && err.status === 401) {
                set({ user: null, isAuthenticated: false, loading: false });
                return;
            }
            set((state) => ({ ...state, loading: false }));
        }
    },
}));