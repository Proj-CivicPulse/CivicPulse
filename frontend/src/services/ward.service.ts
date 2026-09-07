import { get } from './api';
import { SPRING_API_PATH } from '../config/constants';

/**
 * Wards are public municipal reference data — the whole /wards prefix is
 * readable without a session.
 */
export interface Ward {
    id: string;
    /** Stable ward number used in complaint reference numbers, e.g. "17". */
    code: string;
    name: string;
    zone: string | null;
    /** Centroid. Null until a ward is given coordinates. */
    lat: number | null;
    long: number | null;
}

/**
 * The landing page's ward strip.
 *
 * Counts, never rows. This endpoint is unauthenticated, so it deliberately
 * carries no incident titles, coordinates, categories, or priority scores —
 * see the note on the endpoint in backend-spring.
 */
export interface WardSummary {
    wardId: string;
    wardName: string;
    openIncidentCount: number;
}

export const wardService = {
    list: () => get<Ward[]>(SPRING_API_PATH, '/wards'),

    getById: (id: string) => get<Ward>(SPRING_API_PATH, `/wards/${id}`),

    /**
     * Derives the ward from a coordinate pair. Nearest seeded centroid today,
     * behind a resolver seam so real boundary polygons can replace it without
     * touching this call.
     *
     * Throws ApiError 404 when no ward covers the location — the submit flow
     * treats that as "fall back to the ward picker", not as a failure.
     */
    resolve: (lat: number, long: number) =>
        get<Ward>(
            SPRING_API_PATH,
            `/wards/resolve?lat=${encodeURIComponent(lat)}&long=${encodeURIComponent(long)}`
        ),

    summary: () => get<WardSummary[]>(SPRING_API_PATH, '/wards/summary'),
};
