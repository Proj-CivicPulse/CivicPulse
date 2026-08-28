import { get } from './api';
import { SPRING_API_PATH, NODE_API_PATH } from '../config/constants';

export interface HealthStatus {
    status: 'ok' | 'error';
}

export const healthService = {
    checkSpring: (options?: { signal?: AbortSignal }) => get<HealthStatus>(SPRING_API_PATH, '/health', options),
    checkNode: (options?: { signal?: AbortSignal }) => get<HealthStatus>(NODE_API_PATH, '/health', options),
};