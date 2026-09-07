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

    getMe: () => get<AuthUser>(SPRING_API_PATH, '/auth/me', { skipAuth: true }),
};
