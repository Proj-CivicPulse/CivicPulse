package com.civicpulse.backend_spring.service.intelligence;

import com.civicpulse.backend_spring.dto.QueryParams;
import com.civicpulse.backend_spring.dto.Wire;
import com.civicpulse.backend_spring.dto.dashboard.AttentionDto;
import com.civicpulse.backend_spring.repository.AttentionRepository;
import com.civicpulse.backend_spring.repository.AttentionRepository.ActiveIncident;
import com.civicpulse.backend_spring.repository.AttentionRepository.CategoryVolume;
import com.civicpulse.backend_spring.service.category.CategoryService;
import com.civicpulse.backend_spring.service.priority.PriorityBand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * "Needs attention" — docs/roadmap.md, Phase 10. Five deterministic signals,
 * each a statement an officer can open:
 *
 * <ul>
 *   <li><b>life_safety</b> — active, and flagged as a life-safety hazard (V24),
 *       by an officer or by the reports' text. First, because it is the one
 *       signal about harm rather than volume.</li>
 *   <li><b>high_priority</b> — active, in the high or critical band.</li>
 *   <li><b>worsening</b> — {@value #WORSENING_REPORTS}+ new reports in the last
 *       {@value #WORSENING_HOURS} hours. (The plan also named "score up 1.0
 *       since yesterday"; scores are not historised, so that rule would have
 *       to be guessed — it is left out rather than faked.)</li>
 *   <li><b>in_hotspot</b> — a member of a Phase 9 hotspot (last 30 days).</li>
 *   <li><b>category_spike</b> — a category whose last 7 days of reports are at
 *       least {@value #SPIKE_RATIO}× its weekly mean over the 4 weeks before,
 *       and at least {@value #SPIKE_MIN_REPORTS}; its incidents are the active
 *       ones reported in those 7 days.</li>
 * </ul>
 *
 * <p>Ward and category filters narrow every signal the same way.
 */
@Service
@RequiredArgsConstructor
public class AttentionService {

    public static final int WORSENING_REPORTS = 2;
    public static final int WORSENING_HOURS = 72;
    public static final double SPIKE_RATIO = 2.0;
    public static final int SPIKE_MIN_REPORTS = 5;

    private final AttentionRepository attentionRepository;
    private final HotspotService hotspotService;
    private final CategoryService categoryService;

    @Transactional(readOnly = true)
    public AttentionDto attention(List<Long> wardIds, List<String> categories) {
        return attention(wardIds, categories, LocalDateTime.now());
    }

    /** Evaluated as of {@code now}; package-visible so tests can fix the clock. */
    @Transactional(readOnly = true)
    AttentionDto attention(List<Long> wardIds, List<String> categories, LocalDateTime now) {
        List<Long> wards = wardIds == null ? List.of() : wardIds.stream().distinct().toList();
        List<String> canonical = QueryParams.each(categories, categoryService::canonicaliseFilter);

        List<ActiveIncident> active = attentionRepository.activeIncidents(
                wards, canonical, now, now.minusHours(WORSENING_HOURS));
        double high = PriorityBand.HIGH.lowerBound();

        Set<Long> hotspotMembers = hotspotService
                .hotspots(canonical, wards, now.minusDays(HotspotService.DEFAULT_WINDOW_DAYS), now)
                .getHotspots().stream()
                .flatMap(h -> h.getIncidents().stream())
                .map(m -> Long.parseLong(m.getId()))
                .collect(Collectors.toSet());

        List<AttentionDto.Spike> spikes = attentionRepository.categoryVolumes(wards, canonical, now).stream()
                .filter(AttentionService::isSpike)
                .map(v -> new AttentionDto.Spike(v.category(), v.lastWeek(), v.priorFourWeeks() / 4.0))
                .sorted(Comparator.comparingLong(AttentionDto.Spike::getLastWeek).reversed()
                        .thenComparing(AttentionDto.Spike::getCategory))
                .toList();
        Set<String> spiking = spikes.stream().map(AttentionDto.Spike::getCategory).collect(Collectors.toSet());
        LocalDateTime weekAgo = now.minusDays(7);

        List<AttentionDto.Signal> signals = new ArrayList<>();
        signals.add(signal("life_safety",
                "Active incidents whose reports describe a hazard to life: open drain or manhole, exposed "
                        + "wiring, sewage overflow, collapsed road, fallen tree or pole",
                active, ActiveIncident::lifeSafety, List.of()));
        signals.add(signal("high_priority",
                "Active incidents in the high or critical band",
                active, i -> i.priorityScore() >= high, List.of()));
        signals.add(signal("worsening",
                WORSENING_REPORTS + " or more new reports in the last " + WORSENING_HOURS + " hours",
                active, i -> i.recentReports() >= WORSENING_REPORTS, List.of()));
        signals.add(signal("in_hotspot",
                "Part of a hotspot: " + HotspotService.MIN_INCIDENTS + "+ active incidents of one category within "
                        + Math.round(HotspotService.RADIUS_METRES) + " m",
                active, i -> hotspotMembers.contains(i.id()), List.of()));
        signals.add(signal("category_spike",
                "Category with " + SPIKE_MIN_REPORTS + "+ reports this week, at least "
                        + Math.round(SPIKE_RATIO) + "× its usual weekly volume",
                active, i -> spiking.contains(i.category())
                        && i.lastReportedAt() != null && !i.lastReportedAt().isBefore(weekAgo),
                spikes));

        return new AttentionDto(Wire.timestamp(now), signals);
    }

    /** Last 7 days ≥ 2× the prior 4-week weekly mean, and ≥ 5 reports. */
    static boolean isSpike(CategoryVolume v) {
        return v.lastWeek() >= SPIKE_MIN_REPORTS && v.lastWeek() >= SPIKE_RATIO * (v.priorFourWeeks() / 4.0);
    }

    private static AttentionDto.Signal signal(String key, String definition, List<ActiveIncident> active,
                                              Predicate<ActiveIncident> rule, List<AttentionDto.Spike> spikes) {
        List<AttentionDto.Item> items = active.stream()
                .filter(rule)
                .sorted(Comparator.comparingDouble(ActiveIncident::priorityScore).reversed()
                        .thenComparingLong(ActiveIncident::id))
                .map(i -> new AttentionDto.Item(
                        Wire.id(i.id()), Wire.id(i.wardId()), i.wardName(), i.category(), i.address(),
                        i.priorityScore(), Wire.enumValue(PriorityBand.of(i.priorityScore())),
                        i.complaintCount(), i.recentReports(), Wire.timestamp(i.lastReportedAt())))
                .toList();
        return new AttentionDto.Signal(key, definition, items, spikes);
    }
}
