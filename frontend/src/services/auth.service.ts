import { get, post } from './api';
import { SPRING_API_PATH } from '../config/constants';
import type { AuthUser } from '../stores/auth.store';

export const authService = {
    login: (email: string, password: string) =>
        post<{ user: AuthUser }>(SPRING_API_PATH, '/auth/login', { email, password }, { skipAuth: true }),

    logout: () => post<void>(SPRING_API_PATH, '/auth/logout', undefined, { skipAuth: true }),

    getMe: () => get<AuthUser>(SPRING_API_PATH, '/auth/me', { skipAuth: true }),
};