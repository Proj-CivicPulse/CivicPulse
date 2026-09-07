import { get } from './api';
import { SPRING_API_PATH } from '../config/constants';

/** Reference data for the officer's triage picker. Requires a session. */
export interface Department {
    id: string;
    name: string;
}

export const departmentService = {
    list: () => get<Department[]>(SPRING_API_PATH, '/departments'),

    getById: (id: string) => get<Department>(SPRING_API_PATH, `/departments/${id}`),
};
