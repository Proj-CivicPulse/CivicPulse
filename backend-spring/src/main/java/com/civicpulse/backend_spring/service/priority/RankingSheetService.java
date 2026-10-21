package com.civicpulse.backend_spring.service.priority;

import com.civicpulse.backend_spring.entity.Category;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.repository.CategoryRepository;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The calibration ranking sheet — docs/roadmap.md, Phase 8.1.
 *
 * <p>An officer ranks the active queue by hand WITHOUT seeing the formula's
 * answer, so the sheet carries the facts the formula reads (reports, recent
 * reports, days open, spread) and nothing derived from them: no score, no band,
 * and no stored reasons — those are ordered by contribution, which would leak
 * the ranking. Rows come in a fixed pseudo-random order for the same reason:
 * by id they would be by age, by score they would be the answer.
 *
 * <p>{@code withScores} adds the analyst's key — the same rows plus the score
 * and band — for backend-node's {@code npm run eval:priority}, which compares
 * the two. Fetch both in the same minute; the age term moves daily.
 */
@Service
@RequiredArgsConstructor
public class RankingSheetService {

    static final String SHEET_HEADER =
            "incident_id,category,ward,place,reports,reports_last_24h,reports_last_7d,days_open,spread_m";

    private final IncidentRepository incidentRepository;
    private final ComplaintRepository complaintRepository;
    private final CategoryRepository categoryRepository;
    private final PriorityService priorityService;

    @Transactional(readOnly = true)
    public String csv(List<Long> wardIds, boolean withScores) {
        return csv(wardIds, withScores, LocalDateTime.now());
    }

    /** As of {@code now}; package-visible so tests can fix the clock. */
    @Transactional(readOnly = true)
    String csv(List<Long> wardIds, boolean withScores, LocalDateTime now) {
        Map<String, String> categoryNames = categoryRepository.findAllByOrderBySortOrderAscCodeAsc().stream()
                .collect(Collectors.toMap(Category::getCode, Category::getName, (a, b) -> a));

        List<Incident> active = new ArrayList<>(incidentRepository.findByStatus(IncidentStatus.OPEN));
        active.addAll(incidentRepository.findByStatus(IncidentStatus.IN_PROGRESS));
        if (wardIds != null && !wardIds.isEmpty()) {
            active.removeIf(i -> !wardIds.contains(i.getWard().getId()));
        }
        active.sort(Comparator.comparingLong(i -> shuffleKey(i.getId())));

        StringBuilder out = new StringBuilder(SHEET_HEADER)
                .append(withScores ? ",score,band\n" : ",officer_rank\n");
        for (Incident incident : active) {
            List<Complaint> members = complaintRepository.findByIncidentIdOrderByReportedAtAsc(incident.getId());
            PriorityService.Inputs in = priorityService.inputs(incident, members, now);
            out.append(incident.getId()).append(',')
                    .append(field(categoryNames.getOrDefault(incident.getCategory(), incident.getCategory()))).append(',')
                    .append(field(incident.getWard().getName())).append(',')
                    .append(field(incident.getAddress())).append(',')
                    .append(in.reports()).append(',')
                    .append(in.reportsLast24h()).append(',')
                    .append(in.reportsLast7d()).append(',')
                    .append(in.daysOpen()).append(',')
                    .append(Math.round(in.spreadMetres())).append(',');
            if (withScores) {
                PriorityResult result = priorityService.compute(incident, members, now);
                out.append(String.format(Locale.ROOT, "%.1f", result.score())).append(',')
                        .append(result.band().name().toLowerCase(Locale.ROOT));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * A fixed, id-scrambling order: the same sheet twice is the same order,
     * but neighbouring ids (similar ages) do not sit together.
     */
    static long shuffleKey(long id) {
        long x = id * 0x9E3779B97F4A7C15L;
        x ^= (x >>> 31);
        return x * 0xBF58476D1CE4E5B9L;
    }

    /** RFC 4180: quote when needed, double inner quotes; null is empty. */
    static String field(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
