import { get } from './api';
import { SPRING_API_PATH } from '../config/constants';
import type { Incident, IncidentStatus } from './incident.service';
import type { Ward } from './ward.service';
import type { PriorityBand } from '@/lib/priority';

/**
 * The Explorer's aggregate endpoints — docs/api-contract.md, N1–N3 and E6.
 * Officer only (the whole /dashboard prefix).
 *
 * Definitions are the server's and are not re-derived here: "active" is open
 * or in progress, "high priority" is the high or critical band, every date
 * window is on when complaints were REPORTED, and resolution figures count
 * resolved incidents only (closed means no fix).
 */

/** Filters the aggregates share. Each endpoint honours the subset it documents. */
export interface ExplorerWindow {
    /** ISO-8601 instant, inclusive. */
    from?: string;
    /** ISO-8601 instant, exclusive. */
    to?: string;
}

/** N1 — one row per active ward, zeros included. */
export interface WardExplorerSummary {
    wardId: string;
    wardCode: string;
    wardName: string;
    complaintCount: number;
    activeIncidentCount: number;
    highPriorityIncidentCount: number;
    /** Canonical code; null when the ward has no complaints. Render via /categories. */
    dominantCategory: string | null;
    lastActivityAt: string | null;
}

/** N2 — one row per category that is active or has data. */
export interface CategoryExplorerSummary {
    category: string;
    complaintCount: number;
    activeIncidentCount: number;
    highPriorityIncidentCount: number;
    wardCount: number;
    resolvedIncidentCount: number;
    /** Null when nothing was resolved — "no data", never "zero hours". */
    medianHoursToResolve: number | null;
    /** Null means no owning department: triage by hand. */
    defaultDepartmentId: string | null;
    lastActivityAt: string | null;
}

/** N3 — every bucket present in every series, zeros included. */
export interface Trend {
    bucket: 'day' | 'week';
    series: {
        /** Category code when grouped by category; null for the single ungrouped series. */
        key: string | null;
        points: { start: string; count: number }[];
    }[];
}

/** E6 — the ward detail summary. */
export interface WardDashboardSummary {
    ward: Ward;
    // Unfiltered, kept for compatibility.
    totalComplaints: number;
    openComplaints: number;
    openIncidents: number;
    complaintsLast7d: number;
    topCategories: { category: string; count: number }[];
    topIncidents: Incident[];
    // Honour the request's category and from/to filters.
    highPriorityIncidentCount: number;
    lastActivityAt: string | null;
    /** Active incidents per band; every band present. */
    priorityDistribution: Record<PriorityBand, number>;
    /** Incidents per status; every status present. */
    statusDistribution: Record<IncidentStatus, number>;
}

/**
 * Hotspots (docs/roadmap.md, Phase 9): 3+ active incidents of one category
 * within 500 m of each other, each reported in the window (default: the last
 * 30 days). The definition comes back with the data — show it, don't restate it.
 */
export interface Hotspots {
    definition: { minIncidents: number; radiusMetres: number; from: string | null; to: string | null };
    /** Highest priority first. */
    hotspots: Hotspot[];
}

export interface Hotspot {
    /** `<category>-<lowest member incident id>`. */
    id: string;
    category: string;
    incidentCount: number;
    complaintCount: number;
    latitude: number;
    longitude: number;
    /** Centre to farthest member, metres. */
    radiusMetres: number;
    maxPriorityScore: number;
    maxPriorityBand: PriorityBand;
    lastReportedAt: string | null;
    wards: { id: string; name: string }[];
    /** Highest priority first. */
    incidents: HotspotMember[];
}

export interface HotspotMember {
    id: string;
    wardId: string;
    priorityScore: number;
    priorityBand: PriorityBand;
    complaintCount: number;
    latitude: number;
    longitude: number;
    address: string | null;
    lastReportedAt: string | null;
}

/**
 * "Needs attention" (docs/roadmap.md, Phase 10). Always four signals, in this
 * order, empty ones included; each carries the incidents behind it.
 */
export type AttentionKey = 'high_priority' | 'worsening' | 'in_hotspot' | 'category_spike';

export interface Attention {
    asOf: string;
    signals: AttentionSignal[];
}

export interface AttentionSignal {
    key: AttentionKey;
    /** The rule in words, from the server. */
    definition: string;
    /** Highest priority first. */
    incidents: AttentionItem[];
    /** category_spike only. */
    categories: { category: string; lastWeek: number; priorWeeklyMean: number }[];
}

export interface AttentionItem {
    id: string;
    wardId: string;
    wardName: string;
    category: string;
    address: string | null;
    priorityScore: number;
    priorityBand: PriorityBand;
    complaintCount: number;
    /** Member reports in the last 72 hours. */
    recentReports: number;
    lastReportedAt: string | null;
}

function query(params: Record<string, string | string[] | undefined>): string {
    const search = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
        if (value === undefined) continue;
        // Repeatable parameters are sent repeated (?category=a&category=b).
        (Array.isArray(value) ? value : [value]).forEach((v) => search.append(key, v));
    }
    const text = search.toString();
    return text ? `?${text}` : '';
}

export const explorerService = {
    /** N1. Filters: categories, from/to. */
    wards: (filters: ExplorerWindow & { categories?: string[] } = {}) =>
        get<WardExplorerSummary[]>(
            SPRING_API_PATH,
            `/dashboard/wards${query({ category: filters.categories, from: filters.from, to: filters.to })}`
        ),

    /** N2. Filters: wardIds, from/to (resolution figures: resolved in the window). */
    categories: (filters: ExplorerWindow & { wardIds?: string[] } = {}) =>
        get<CategoryExplorerSummary[]>(
            SPRING_API_PATH,
            `/dashboard/categories${query({ wardId: filters.wardIds, from: filters.from, to: filters.to })}`
        ),

    /** N3. from and to are required; at most 400 buckets per series. */
    trends: (filters: {
        from: string;
        to: string;
        bucket?: 'day' | 'week';
        groupBy?: 'none' | 'category';
        wardIds?: string[];
        categories?: string[];
    }) =>
        get<Trend>(
            SPRING_API_PATH,
            `/dashboard/trends${query({
                from: filters.from,
                to: filters.to,
                bucket: filters.bucket,
                groupBy: filters.groupBy,
                wardId: filters.wardIds,
                category: filters.categories,
            })}`
        ),

    /** Phase 9. Without from/to the server applies its default window (30 days). */
    hotspots: (filters: ExplorerWindow & { categories?: string[]; wardIds?: string[] } = {}) =>
        get<Hotspots>(
            SPRING_API_PATH,
            `/dashboard/hotspots${query({
                category: filters.categories,
                wardId: filters.wardIds,
                from: filters.from,
                to: filters.to,
            })}`
        ),

    /** Phase 10. Filters: wardIds, categories. Each signal has its own window. */
    attention: (filters: { wardIds?: string[]; categories?: string[] } = {}) =>
        get<Attention>(
            SPRING_API_PATH,
            `/dashboard/attention${query({ wardId: filters.wardIds, category: filters.categories })}`
        ),

    /** E6. 404 for an unknown or retired ward. */
    wardSummary: (wardId: string, filters: ExplorerWindow & { categories?: string[] } = {}) =>
        get<WardDashboardSummary>(
            SPRING_API_PATH,
            `/dashboard/wards/${encodeURIComponent(wardId)}/summary${query({
                category: filters.categories,
                from: filters.from,
                to: filters.to,
            })}`
        ),
};
