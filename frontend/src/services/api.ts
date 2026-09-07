import { REQUEST_TIMEOUT_MS, SPRING_API_PATH } from '../config/constants';
import { useAuthStore } from '../stores/auth.store';

export class ApiError extends Error {
    status: number;
    constructor(message: string, status: number) {
        super(message);
        this.name = 'ApiError';
        this.status = status;
    }
}

interface RequestOptions {
    method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
    body?: unknown;
    headers?: Record<string, string>;
    signal?: AbortSignal;
    skipAuth?: boolean; // true for login/getMe calls that expect a plain 401, not a refresh/redirect
    _retryAfterRefresh?: boolean; // internal — prevents infinite refresh loops
}

// Session refresh is centralized on Spring (per docs/service-boundaries.md
// — Spring owns auth), even for requests originally sent to the Node
// service. Concurrent 401s share one in-flight refresh instead of each
// firing their own.
let refreshInFlight: Promise<boolean> | null = null;

async function tryRefreshSession(): Promise<boolean> {
    if (refreshInFlight) return refreshInFlight;

    refreshInFlight = (async () => {
        try {
            // Public by design — the access token is expected to be expired
            // here. Spring rotates both cookies and returns 401 if the refresh
            // cookie is missing or invalid, at which point callers fall through
            // to the logout/redirect path.
            const response = await fetch(`${SPRING_API_PATH}/auth/refresh`, {
                method: 'POST',
                credentials: 'include',
            });
            return response.ok;
        } catch {
            return false;
        } finally {
            refreshInFlight = null;
        }
    })();

    return refreshInFlight;
}

async function request<T>(baseUrl: string, path: string, options: RequestOptions = {}): Promise<T> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

    try {
        const response = await fetch(`${baseUrl}${path}`, {
            method: options.method ?? 'GET',
            headers: {
                'Content-Type': 'application/json',
                ...options.headers,
            },
            body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
            signal: options.signal ?? controller.signal,
            credentials: 'include', // httpOnly session cookie, set by Spring
        });

        if (response.status === 401) {
            if (options.skipAuth) {
                throw new ApiError('Not authenticated', 401);
            }

            if (!options._retryAfterRefresh) {
                const refreshed = await tryRefreshSession();
                if (refreshed) {
                    return request<T>(baseUrl, path, { ...options, _retryAfterRefresh: true });
                }
            }

            // Note: this module and stores/auth.store.ts import each other
            // (api.ts -> auth.store -> auth.service -> api.ts). That's fine in
            // ES modules as long as nothing is read at module-evaluation time —
            // getState() below only runs once this function is actually
            // called, by which point every module has finished initializing.
            useAuthStore.getState().logout();
            throw new ApiError('Session expired. Please log in again.', 401);
        }

        if (!response.ok) {
            let message = `Request failed with status ${response.status}`;
            try {
                const errorBody = await response.json();
                if (typeof errorBody?.error?.message === 'string') {
                    message = errorBody.error.message;
                }
            } catch {
                // response wasn't JSON — keep the generic message
            }
            throw new ApiError(message, response.status);
        }

        if (response.status === 204) {
            return undefined as T;
        }

        return (await response.json()) as T;
    } catch (err) {
        if (err instanceof ApiError) throw err;
        if (err instanceof DOMException && err.name === 'AbortError') {
            throw new ApiError('Request timed out', 408);
        }
        throw new ApiError('Network error — please check your connection', 0);
    } finally {
        clearTimeout(timeout);
    }
}

type Opts = Omit<RequestOptions, 'method' | 'body'>;

export function get<T>(baseUrl: string, path: string, options?: Opts): Promise<T> {
    return request<T>(baseUrl, path, { ...options, method: 'GET' });
}
export function post<T>(baseUrl: string, path: string, body: unknown, options?: Opts): Promise<T> {
    return request<T>(baseUrl, path, { ...options, method: 'POST', body });
}
export function put<T>(baseUrl: string, path: string, body: unknown, options?: Opts): Promise<T> {
    return request<T>(baseUrl, path, { ...options, method: 'PUT', body });
}
export function patch<T>(baseUrl: string, path: string, body: unknown, options?: Opts): Promise<T> {
    return request<T>(baseUrl, path, { ...options, method: 'PATCH', body });
}
export function del<T>(baseUrl: string, path: string, options?: Opts): Promise<T> {
    return request<T>(baseUrl, path, { ...options, method: 'DELETE' });
}