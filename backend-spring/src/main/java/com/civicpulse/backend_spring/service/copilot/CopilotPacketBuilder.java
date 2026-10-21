package com.civicpulse.backend_spring.service.copilot;

import com.civicpulse.backend_spring.dto.PageResponse;
import com.civicpulse.backend_spring.dto.QueryParams;
import com.civicpulse.backend_spring.dto.copilot.CopilotAnswerDto;
import com.civicpulse.backend_spring.dto.copilot.CopilotQueryRequest;
import com.civicpulse.backend_spring.dto.dashboard.CategoryExplorerSummaryDto;
import com.civicpulse.backend_spring.dto.dashboard.TrendDto;
import com.civicpulse.backend_spring.dto.dashboard.WardDashboardSummaryDto;
import com.civicpulse.backend_spring.dto.dashboard.WardExplorerSummaryDto;
import com.civicpulse.backend_spring.dto.incident.IncidentDto;
import com.civicpulse.backend_spring.dto.ward.WardDto;
import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.entity.Department;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.DepartmentRepository;
import com.civicpulse.backend_spring.service.category.CategoryService;
import com.civicpulse.backend_spring.service.dashboard.DashboardService;
import com.civicpulse.backend_spring.service.explorer.ExplorerService;
import com.civicpulse.backend_spring.service.incident.IncidentQuery;
import com.civicpulse.backend_spring.service.incident.IncidentService;
import com.civicpulse.backend_spring.service.ward.WardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds the Copilot's grounding packet: everything the model may use to
 * answer, and nothing else (docs/next-phases-plan.md §2).
 *
 * <p>Every figure comes from the service that serves the same figure to the
 * Explorer, so "active", "high priority" and "last 30 days" mean exactly what
 * they mean on the dashboard — the Copilot cannot disagree with the screen
 * the officer is looking at.
 *
 * <p>Every citable entry gets an id ({@code incident:45}, {@code ward:538},
 * {@code category:pothole}, {@code metric:active_incidents}) recorded in
 * {@link Packet#sources()}. That index is the whole of what an answer may
 * cite; {@link CopilotService} drops anything else.
 *
 * <p>Resident complaint text is deliberately NOT included: only incident-level
 * fields. It keeps residents' words out of a third-party request until the
 * team decides otherwise, and keeps the packet small.
 */
@Component
@RequiredArgsConstructor
public class CopilotPacketBuilder {

    /** A geocoder plus code ("4J24+6C6") that leads an address when there is no street. */
    private static final Pattern PLUS_CODE =
            Pattern.compile("^[23456789CFGHJMPQRVWX]{4,8}\\+[23456789CFGHJMPQRVWX]{2,3}\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_WORD = Pattern.compile("[A-Za-z]{3,}");

    private final ExplorerService explorerService;
    private final DashboardService dashboardService;
    private final IncidentService incidentService;
    private final CategoryService categoryService;
    private final WardService wardService;
    private final DepartmentRepository departmentRepository;
    private final AppProperties appProperties;

    /** The packet sent to the model, the ids it may cite, and the scope it describes. */
    public record Packet(
            Map<String, Object> body,
            Map<String, CopilotAnswerDto.Source> sources,
            CopilotAnswerDto.Scope scope) {
    }

    @Transactional(readOnly = true)
    public Packet build(CopilotQueryRequest request) {
        AppProperties.Copilot config = appProperties.getCopilot();

        Long wardId = parseWardId(request.getWardId());
        String category = request.getCategory() == null || request.getCategory().isBlank()
                ? null
                : categoryService.canonicaliseFilter(request.getCategory());
        // Validates the instants and their order once, the same way every
        // Explorer endpoint does.
        QueryParams.requireOrderedWindow(
                QueryParams.instant(request.getFrom(), "from"),
                QueryParams.instant(request.getTo(), "to"));
        String from = blankToNull(request.getFrom());
        String to = blankToNull(request.getTo());

        List<Long> wardIds = wardId == null ? null : List.of(wardId);
        List<String> categories = category == null ? null : List.of(category);

        Map<String, String> wardNames = wardService.list().stream()
                .collect(Collectors.toMap(WardDto::getId, WardDto::getName, (a, b) -> a));
        Map<String, String> categoryNames = new LinkedHashMap<>();
        categoryService.listActive().forEach(c -> categoryNames.put(c.getCode(), c.getName()));
        Function<String, String> categoryName = code -> categoryNames.getOrDefault(code, code);
        Map<String, String> departmentNames = departmentRepository.findAll().stream()
                .collect(Collectors.toMap(d -> String.valueOf(d.getId()), Department::getName, (a, b) -> a));

        Map<String, CopilotAnswerDto.Source> sources = new LinkedHashMap<>();
        Map<String, Object> body = new LinkedHashMap<>();

        // A ward summary 404s for an unknown or retired ward — the same answer
        // the ward detail page gives, before any model is called.
        WardDashboardSummaryDto wardSummary = wardId == null
                ? null
                : dashboardService.wardSummary(wardId, categories, from, to);
        String wardName = wardSummary == null ? null : wardSummary.getWard().getName();

        CopilotAnswerDto.Scope scope = new CopilotAnswerDto.Scope(
                wardId == null ? null : String.valueOf(wardId), wardName,
                category, category == null ? null : categoryName.apply(category), from, to);

        body.put("generatedAt", java.time.Instant.now().toString());
        Map<String, Object> scopeOut = new LinkedHashMap<>();
        scopeOut.put("ward", wardName == null ? "all wards (city-wide)" : wardName + " (ward " + wardSummary.getWard().getCode() + ")");
        scopeOut.put("category", category == null ? "all categories" : categoryName.apply(category));
        scopeOut.put("reportedFrom", from);
        scopeOut.put("reportedTo", to);
        scopeOut.put("window", from == null && to == null ? "all time" : "reports in [reportedFrom, reportedTo)");
        body.put("scope", scopeOut);
        body.put("definitions", List.of(
                "active = status open or in progress",
                "high priority = priority band high or critical (score >= 5.5 on a 0-10 scale)",
                "fixed = status resolved; closed means ended WITHOUT a fix and never counts as fixed",
                "time to fix runs from an incident's first report to its resolution; recorded from 2026-10-06 only",
                "an incident groups complaints about one problem; complaintCount is how many residents reported it",
                "incidents have no titles yet; 'name' is the category plus the nearest place"));

        // ── Headline metrics, from the per-category summary under the same filters ──
        List<CategoryExplorerSummaryDto> categoryRows = explorerService.categorySummaries(wardIds, from, to);
        List<CategoryExplorerSummaryDto> inScope = category == null
                ? categoryRows
                : categoryRows.stream().filter(r -> r.getCategory().equals(category)).toList();
        List<Map<String, Object>> metrics = new ArrayList<>();
        metric(metrics, sources, "complaints", "Complaints reported in scope",
                inScope.stream().mapToLong(CategoryExplorerSummaryDto::getComplaintCount).sum());
        metric(metrics, sources, "active_incidents", "Active incidents in scope",
                inScope.stream().mapToLong(CategoryExplorerSummaryDto::getActiveIncidentCount).sum());
        metric(metrics, sources, "high_priority_incidents", "Active high-priority incidents in scope",
                inScope.stream().mapToLong(CategoryExplorerSummaryDto::getHighPriorityIncidentCount).sum());
        metric(metrics, sources, "fixed_incidents", "Incidents resolved (fixed) in scope",
                inScope.stream().mapToLong(CategoryExplorerSummaryDto::getResolvedIncidentCount).sum());
        if (wardSummary != null) {
            metric(metrics, sources, "status_breakdown", "Incidents by status in this ward", wardSummary.getStatusDistribution());
            metric(metrics, sources, "priority_breakdown", "Active incidents by priority band in this ward",
                    wardSummary.getPriorityDistribution());
        }
        body.put("metrics", metrics);

        // ── The most urgent active incidents ──
        PageResponse<IncidentDto> page = incidentService.search(IncidentQuery.parse(
                wardIds, categories, List.of("open", "in_progress"), null, null,
                from, to, null, "priority", null, config.getPacketIncidents(), null));
        List<Map<String, Object>> incidents = new ArrayList<>();
        for (IncidentDto incident : page.getItems()) {
            String id = "incident:" + incident.getId();
            String name = incidentName(incident, categoryName);
            sources.put(id, new CopilotAnswerDto.Source(id, "incident", name, incident.getId()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", name);
            row.put("category", categoryName.apply(incident.getCategory()));
            row.put("ward", wardNames.getOrDefault(incident.getWardId(), "ward " + incident.getWardId()));
            row.put("status", incident.getStatus());
            row.put("priorityScore", incident.getPriorityScore());
            row.put("priorityBand", incident.getPriorityBand());
            row.put("priorityReasons", incident.getPriorityReasons());
            row.put("complaintCount", incident.getComplaintCount());
            row.put("firstReportedAt", incident.getFirstReportedAt());
            row.put("lastReportedAt", incident.getLastReportedAt());
            row.put("owningDepartment", incident.getOwningDepartmentId() == null
                    ? "none - triage by hand"
                    : departmentNames.getOrDefault(incident.getOwningDepartmentId(), "department " + incident.getOwningDepartmentId()));
            row.put("address", incident.getAddress());
            incidents.add(row);
        }
        Map<String, Object> incidentSection = new LinkedHashMap<>();
        incidentSection.put("totalActiveInScope", page.getTotal());
        incidentSection.put("shown", incidents.size());
        incidentSection.put("order", "most urgent first (priority score, then most recent report)");
        incidentSection.put("items", incidents);
        body.put("activeIncidents", incidentSection);

        // ── Per category ──
        List<Map<String, Object>> categoryOut = new ArrayList<>();
        for (CategoryExplorerSummaryDto row : inScope) {
            if (row.getComplaintCount() == 0 && row.getActiveIncidentCount() == 0) continue;
            String id = "category:" + row.getCategory();
            String label = categoryName.apply(row.getCategory());
            sources.put(id, new CopilotAnswerDto.Source(id, "category", label, row.getCategory()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("name", label);
            out.put("complaints", row.getComplaintCount());
            out.put("activeIncidents", row.getActiveIncidentCount());
            out.put("highPriorityIncidents", row.getHighPriorityIncidentCount());
            out.put("wardsAffected", row.getWardCount());
            out.put("fixedIncidents", row.getResolvedIncidentCount());
            out.put("medianHoursToFix", row.getMedianHoursToResolve());
            out.put("defaultDepartment", row.getDefaultDepartmentId() == null
                    ? "none - triage by hand"
                    : departmentNames.getOrDefault(row.getDefaultDepartmentId(), "department " + row.getDefaultDepartmentId()));
            out.put("lastReportedAt", row.getLastActivityAt());
            categoryOut.add(out);
        }
        body.put("categories", categoryOut);

        // ── Busiest wards (city scope only — a ward view is already one ward) ──
        if (wardId == null) {
            List<WardExplorerSummaryDto> wards = explorerService.wardSummaries(categories, from, to).stream()
                    .filter(w -> w.getComplaintCount() > 0 || w.getActiveIncidentCount() > 0)
                    .sorted(Comparator.comparingLong(WardExplorerSummaryDto::getActiveIncidentCount).reversed()
                            .thenComparing(Comparator.comparingLong(WardExplorerSummaryDto::getComplaintCount).reversed()))
                    .limit(config.getPacketWards())
                    .toList();
            List<Map<String, Object>> wardOut = new ArrayList<>();
            for (WardExplorerSummaryDto w : wards) {
                String id = "ward:" + w.getWardId();
                String label = w.getWardName() + " (ward " + w.getWardCode() + ")";
                sources.put(id, new CopilotAnswerDto.Source(id, "ward", label, w.getWardId()));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", id);
                out.put("name", label);
                out.put("complaints", w.getComplaintCount());
                out.put("activeIncidents", w.getActiveIncidentCount());
                out.put("highPriorityIncidents", w.getHighPriorityIncidentCount());
                out.put("mainCategory", w.getDominantCategory() == null ? null : categoryName.apply(w.getDominantCategory()));
                out.put("lastReportedAt", w.getLastActivityAt());
                wardOut.add(out);
            }
            body.put("busiestWards", wardOut);
        } else {
            String id = "ward:" + wardId;
            sources.put(id, new CopilotAnswerDto.Source(id, "ward", wardName, String.valueOf(wardId)));
        }

        // ── Recent trend ──
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        String trendTo = tomorrow + "T00:00:00Z";
        String trendFrom = tomorrow.minusDays(config.getTrendDays()).format(DateTimeFormatter.ISO_LOCAL_DATE) + "T00:00:00Z";
        TrendDto trend = explorerService.trends(trendFrom, trendTo, "day", "none", wardIds, categories);
        sources.put("metric:trend", new CopilotAnswerDto.Source("metric:trend", "metric",
                "Complaints reported per day, last " + config.getTrendDays() + " days", null));
        Map<String, Object> trendOut = new LinkedHashMap<>();
        trendOut.put("id", "metric:trend");
        trendOut.put("description", "complaints reported per day, last " + config.getTrendDays()
                + " days, regardless of the window above");
        trendOut.put("points", trend.getSeries().isEmpty() ? List.of() : trend.getSeries().get(0).getPoints().stream()
                .map(p -> Map.of("day", p.getStart().substring(0, 10), "complaints", p.getCount()))
                .toList());
        body.put("recentTrend", trendOut);

        return new Packet(body, sources, scope);
    }

    private static void metric(List<Map<String, Object>> metrics, Map<String, CopilotAnswerDto.Source> sources,
                               String key, String label, Object value) {
        String id = "metric:" + key;
        sources.put(id, new CopilotAnswerDto.Source(id, "metric", label, null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("label", label);
        out.put("value", value);
        metrics.add(out);
    }

    /**
     * Same naming as the frontend's incidentName: titles are null today, so an
     * incident is its category plus the first address part that is a real
     * place — skipping house numbers and leading plus codes.
     */
    static String incidentName(IncidentDto incident, Function<String, String> categoryName) {
        if (incident.getTitle() != null && !incident.getTitle().isBlank()) return incident.getTitle();
        String place = placeName(incident.getAddress());
        String category = categoryName.apply(incident.getCategory());
        return place == null ? category : category + " · " + place;
    }

    static String placeName(String address) {
        if (address == null) return null;
        for (String raw : address.split(",")) {
            String part = PLUS_CODE.matcher(raw.trim()).replaceFirst("").trim();
            if (HAS_WORD.matcher(part).find()) return part;
        }
        return null;
    }

    private static Long parseWardId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            throw new ValidationException("wardId must be a ward id");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
