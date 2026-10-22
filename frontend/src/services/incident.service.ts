import { get, patch, post, type Page } from './api';
import { SPRING_API_PATH } from '../config/constants';
import type { Complaint } from './complaint.service';
import type { PriorityBand } from '@/lib/priority';

export type IncidentStatus = 'open' | 'in_progress' | 'resolved' | 'closed';

/**
 * Wire shape per docs/api-contract.md.
 *
 * An incident is the unit officers act on: a group of related complaints
 * carrying a priority score AND the human-readable reasons for it.
 */
export interface Incident {
    id: string;
    wardId: string;
    departmentId: string | null;
    /**
     * Whose queue this is: the officer-assigned department, else the
     * category's default. Null means no owner — triage by hand.
     */
    owningDepartmentId: string | null;
    title: string | null;
    summary: string | null;
    category: string;
    priorityScore: number;
    /** Computed server-side; the thresholds belong to the model, not the client. */
    priorityBand: PriorityBand;
    /**
     * The project's headline explainability claim. Human-readable evidence
     * strings, never codes and never weights. Render them; if the array is
     * empty, say so explicitly rather than hiding the section.
     */
    priorityReasons: string[];
    status: IncidentStatus;
    /** Street address of the centroid. Null when geocoding is disabled. */
    address: string | null;
    complaintCount: number;
    lat: number | null;
    long: number | null;
    /** Node writes this in Phase 6. Null until then. */
    aiRecommendation: string | null;
    /**
     * When this incident was resolved. Null at every other status — including
     * one that was resolved and then reopened, which has no resolution time.
     *
     * Do NOT compute resolution time as `updatedAt - createdAt`: the priority
     * refresh job touches every open incident every few minutes, so that
     * subtraction measures the sweep. This field is the only honest answer.
     */
    resolvedAt: string | null;
    /**
     * When the problem was first / last reported — min/max of the members'
     * `reportedAt`. Measure an incident's age from `firstReportedAt`:
     * `createdAt` is when the grouping was made, which for an imported report
     * can be months later. Null only on an incident with no members.
     */
    firstReportedAt: string | null;
    lastReportedAt: string | null;
    /**
     * A hazard to life: open drain or manhole, exposed wiring, sewage
     * overflow, collapsed road, fallen tree or pole. `report_text` means the
     * reports suggested it and an officer has not decided yet; `officer` means
     * decided (either way). Feeds the suggestion, never the score.
     */
    lifeSafety: boolean;
    lifeSafetySource: 'report_text' | 'officer' | null;
    lifeSafetyReason: string | null;
    createdAt: string;
    updatedAt: string;
}

/**
 * GET /incidents parameters (docs/api-contract.md, E1). Every list filter is
 * OR within itself and AND across the others; `from`/`to` select incidents
 * with a member reported in [from, to).
 */
export interface IncidentFilters {
    wardIds?: string[];
    categories?: string[];
    statuses?: IncidentStatus[];
    priorityBands?: PriorityBand[];
    from?: string;
    to?: string;
    q?: string;
    sort?: 'priority' | 'lastReportedAt' | 'firstReportedAt' | 'complaintCount' | 'resolvedAt';
    dir?: 'asc' | 'desc';
    /** Server default 50, server max 200. */
    limit?: number;
    offset?: number;
}

export interface UpdateIncidentInput {
    status?: IncidentStatus;
    departmentId?: string;
    title?: string;
    summary?: string;
    /** The officer's decision on the life-safety flag; final once sent. */
    lifeSafety?: boolean;
    lifeSafetyNote?: string;
}

function buildQuery(filters: IncidentFilters = {}): string {
    const params = new URLSearchParams();
    // Repeatable parameters are sent repeated (?status=open&status=in_progress).
    filters.wardIds?.forEach((id) => params.append('wardId', id));
    filters.categories?.forEach((code) => params.append('category', code));
    filters.statuses?.forEach((status) => params.append('status', status));
    filters.priorityBands?.forEach((band) => params.append('priorityBand', band));
    if (filters.from) params.set('from', filters.from);
    if (filters.to) params.set('to', filters.to);
    if (filters.q) params.set('q', filters.q);
    if (filters.sort) params.set('sort', filters.sort);
    if (filters.dir) params.set('dir', filters.dir);
    if (filters.limit !== undefined) params.set('limit', String(filters.limit));
    if (filters.offset !== undefined) params.set('offset', String(filters.offset));
    const query = params.toString();
    return query ? `?${query}` : '';
}

/** Decision support (docs/roadmap.md, Phase 11). */
export type RecommendedAction = 'escalate' | 'dispatch' | 'verify_and_close' | 'consolidate' | 'monitor';

export interface Recommendation {
    incidentId: string;
    /** Null for a resolved or closed incident — the explanation says so. */
    action: RecommendedAction | null;
    ruleId: string | null;
    explanation: string;
    /** The facts the rules read, as short lines. */
    facts: string[];
    asOf: string;
    lastFeedback: {
        decision: 'accepted' | 'overridden';
        recommendedAction: RecommendedAction;
        chosenAction: RecommendedAction | null;
        note: string | null;
        userId: string;
        createdAt: string;
    } | null;
}

export interface RecommendationFeedback {
    decision: 'accepted' | 'overridden';
    chosenAction?: RecommendedAction;
    note?: string;
}

/**
 * Why one report is part of its incident, in an officer's terms (distance,
 * wording, who decided) — never a similarity score.
 */
export interface GroupingMember {
    complaintId: string;
    referenceNo: string;
    kind: 'started' | 'joined' | 'backup_rule' | 'rechecked' | 'merged' | 'split' | 'unknown';
    explanation: string;
    siblingReferenceNo: string | null;
    distanceM: number | null;
    decidedAt: string | null;
}

/** After a merge or unlink: both incidents as they now are. `source` is null after a merge. */
export interface MembershipChange {
    source: Incident | null;
    target: Incident;
}

/** Every endpoint here requires the officer role. */
export const incidentService = {
    list: (filters?: IncidentFilters) =>
        get<Page<Incident>>(SPRING_API_PATH, `/incidents${buildQuery(filters)}`),

    getById: (id: string) => get<Incident>(SPRING_API_PATH, `/incidents/${id}`),

    getComplaints: (id: string) =>
        get<Complaint[]>(SPRING_API_PATH, `/incidents/${id}/complaints`),

    update: (id: string, changes: UpdateIncidentInput) =>
        patch<Incident>(SPRING_API_PATH, `/incidents/${id}`, changes),

    grouping: (id: string) =>
        get<{ incidentId: string; members: GroupingMember[] }>(SPRING_API_PATH, `/incidents/${id}/grouping`),

    /** Same ward, same category, both active; the emptied incident is deleted. */
    merge: (id: string, intoIncidentId: string, note?: string) =>
        post<MembershipChange>(SPRING_API_PATH, `/incidents/${id}/merge`, { intoIncidentId, ...(note ? { note } : {}) }),

    /** Moves one report into a new incident of its own. */
    unlink: (id: string, complaintId: string, note?: string) =>
        post<MembershipChange>(SPRING_API_PATH, `/incidents/${id}/complaints/${complaintId}/unlink`, note ? { note } : {}),

    recommendation: (id: string) => get<Recommendation>(SPRING_API_PATH, `/incidents/${id}/recommendation`),

    /** Logged only: the incident itself is not changed. */
    recommendationFeedback: (id: string, feedback: RecommendationFeedback) =>
        post<Recommendation>(SPRING_API_PATH, `/incidents/${id}/recommendation/feedback`, feedback),
};
