import { get, post } from './api';
import { SPRING_API_PATH } from '../config/constants';
import type { AuthUser } from '../stores/auth.store';

/**
 * `skipAuth` on every call is deliberate: these endpoints are where a 401 is a
 * normal answer ("not logged in"), not a session that needs refreshing. Without
 * it, checking auth on boot would trigger a pointless refresh attempt.
 *
 * Note the shape asymmetry, which is the server's contract and not a bug here:
 * login/register/refresh wrap the user in `{ user }`, but /auth/me returns it
 * bare.
 */
export const authService = {
    login: (email: string, password: string) =>
        post<{ user: AuthUser }>(SPRING_API_PATH, '/auth/login', { email, password }, { skipAuth: true }),

    /** Always creates a citizen. Officer accounts are provisioned by hand. */
    register: (name: string, email: string, password: string) =>
        post<{ user: AuthUser }>(SPRING_API_PATH, '/auth/register', { name, email, password }, { skipAuth: true }),

    logout: () => post<void>(SPRING_API_PATH, '/auth/logout', undefined, { skipAuth: true }),

    /**
     * Ends every session for the account, not just this browser's — the thing
     * to offer when someone thinks their password has been seen. Needs a live
     * session (revoking all of a user's sessions must be something only that
     * user can ask for), so no `skipAuth` here: a 401 should refresh and retry
     * rather than be swallowed.
     *
     * No UI yet — this is the API half, ready to hang a
     * "Sign out on all devices" control off in account settings.
     */
    logoutAll: () => post<void>(SPRING_API_PATH, '/auth/logout-all', undefined),

    getMe: () => get<AuthUser>(SPRING_API_PATH, '/auth/me', { skipAuth: true }),

    /**
     * Redeems the link from a verification email. `skipAuth` because the holder
     * is often signed out — the token itself is the credential, and a 401 here
     * would mean the token was rejected, not that a session needs refreshing.
     */
    verifyEmail: (token: string) =>
        post<void>(SPRING_API_PATH, '/auth/verify-email', { token }, { skipAuth: true }),

    /** Needs a session: the address is taken from it, never from the caller. */
    resendVerification: () =>
        post<void>(SPRING_API_PATH, '/auth/resend-verification', undefined),

    /**
     * Always resolves for a well-formed address, whether or not an account
     * exists — the server deliberately does not say which, so the UI must not
     * imply it either.
     */
    forgotPassword: (email: string) =>
        post<void>(SPRING_API_PATH, '/auth/forgot-password', { email }, { skipAuth: true }),

    /** Signs out every session on success, including any this browser held. */
    resetPassword: (token: string, password: string) =>
        post<void>(SPRING_API_PATH, '/auth/reset-password', { token, password }, { skipAuth: true }),
};
