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
    /** A point guaranteed inside the ward (not the geometric centroid). */
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
     * Derives the ward from a coordinate pair: point-in-polygon against the
     * 243 BBMP boundaries.
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

    /**
     * Active ward boundaries as GeoJSON for the Explorer map (public; cached a
     * day). Simplified for display — never use it to decide a ward. The
     * licence requires `attribution` to be shown on the map.
     */
    boundaries: () => get<WardBoundaries>(SPRING_API_PATH, '/wards/boundaries'),
};

export interface WardBoundaries {
    type: 'FeatureCollection';
    datasetVersion: string;
    attribution: string;
    features: {
        type: 'Feature';
        geometry: { type: 'Polygon' | 'MultiPolygon'; coordinates: unknown };
        properties: { id: string; code: string; name: string };
    }[];
}
