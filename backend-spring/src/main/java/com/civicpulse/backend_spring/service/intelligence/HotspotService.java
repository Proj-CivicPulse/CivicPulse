package com.civicpulse.backend_spring.service.intelligence;

import com.civicpulse.backend_spring.dto.QueryParams;
import com.civicpulse.backend_spring.dto.Wire;
import com.civicpulse.backend_spring.dto.dashboard.HotspotsDto;
import com.civicpulse.backend_spring.repository.HotspotRepository;
import com.civicpulse.backend_spring.repository.HotspotRepository.Member;
import com.civicpulse.backend_spring.service.category.CategoryService;
import com.civicpulse.backend_spring.service.priority.PriorityBand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hotspots — docs/roadmap.md, Phase 9.
 *
 * <p><b>Definition.</b> A hotspot is at least {@value #MIN_INCIDENTS} active
 * incidents of the same category, chained within {@value #RADIUS_METRES} m
 * of each other (DBSCAN over incident centres), each with a report in the
 * window — by default the last {@value #DEFAULT_WINDOW_DAYS} days.
 *
 * <p>Why these numbers: 500 m is the radius the matching evaluation measured
 * for "same problem" (every true join was within 202 m; the distance check
 * cuts at 500 m), so a hotspot is several <em>different</em> problems of one
 * kind in one walkable patch — which the matcher keeps apart and an officer
 * would still want to see together. Three is the smallest count that is a
 * pattern rather than a coincidence. Rule-based on purpose: the incident set
 * is far too small to train anything, and an officer can check every member.
 */
@Service
@RequiredArgsConstructor
public class HotspotService {

    public static final int MIN_INCIDENTS = 3;
    public static final double RADIUS_METRES = 500;
    public static final int DEFAULT_WINDOW_DAYS = 30;

    private final HotspotRepository hotspotRepository;
    private final CategoryService categoryService;

    /**
     * @param wardIds keeps hotspots with at least one member in these wards.
     *                Applied after clustering, so a hotspot that straddles a
     *                ward line is shown whole rather than cut in two.
     */
    @Transactional(readOnly = true)
    public HotspotsDto hotspots(List<String> categories, List<Long> wardIds, String from, String to) {
        List<String> canonical = QueryParams.each(categories, categoryService::canonicaliseFilter);
        LocalDateTime fromAt = QueryParams.instant(from, "from");
        LocalDateTime toAt = QueryParams.instant(to, "to");
        if (fromAt == null && toAt == null) {
            fromAt = LocalDateTime.now().minusDays(DEFAULT_WINDOW_DAYS);
        }
        QueryParams.requireOrderedWindow(fromAt, toAt);

        List<HotspotsDto.Hotspot> hotspots = group(
                hotspotRepository.clusteredIncidents(canonical, fromAt, toAt, RADIUS_METRES, MIN_INCIDENTS));
        if (wardIds != null && !wardIds.isEmpty()) {
            List<String> wanted = wardIds.stream().map(String::valueOf).toList();
            hotspots = hotspots.stream()
                    .filter(h -> h.getWards().stream().anyMatch(w -> wanted.contains(w.getId())))
                    .toList();
        }
        return new HotspotsDto(
                new HotspotsDto.Definition(MIN_INCIDENTS, RADIUS_METRES, Wire.timestamp(fromAt), Wire.timestamp(toAt)),
                hotspots);
    }

    /** Folds clustered rows into hotspots, highest-priority first. */
    static List<HotspotsDto.Hotspot> group(List<Member> members) {
        Map<String, List<Member>> byCluster = new LinkedHashMap<>();
        for (Member m : members) {
            byCluster.computeIfAbsent(m.category() + "#" + m.clusterId(), k -> new ArrayList<>()).add(m);
        }

        List<HotspotsDto.Hotspot> hotspots = new ArrayList<>();
        for (List<Member> cluster : byCluster.values()) {
            double lat = cluster.stream().mapToDouble(Member::latitude).average().orElseThrow();
            double lng = cluster.stream().mapToDouble(Member::longitude).average().orElseThrow();
            double radius = cluster.stream()
                    .mapToDouble(m -> metres(lat, lng, m.latitude(), m.longitude())).max().orElse(0);
            double maxScore = cluster.stream().mapToDouble(Member::priorityScore).max().orElse(0);
            LocalDateTime last = cluster.stream().map(Member::lastReportedAt)
                    .filter(t -> t != null).max(Comparator.naturalOrder()).orElse(null);
            long lowestId = cluster.stream().mapToLong(Member::incidentId).min().orElseThrow();

            Map<Long, String> wards = new LinkedHashMap<>();
            cluster.forEach(m -> wards.putIfAbsent(m.wardId(), m.wardName()));

            List<HotspotsDto.Member> incidents = cluster.stream()
                    .sorted(Comparator.comparingDouble(Member::priorityScore).reversed()
                            .thenComparingLong(Member::incidentId))
                    .map(m -> new HotspotsDto.Member(
                            Wire.id(m.incidentId()), Wire.id(m.wardId()), m.priorityScore(),
                            Wire.enumValue(PriorityBand.of(m.priorityScore())), m.complaintCount(),
                            m.latitude(), m.longitude(), m.address(), Wire.timestamp(m.lastReportedAt())))
                    .toList();

            hotspots.add(new HotspotsDto.Hotspot(
                    cluster.getFirst().category() + "-" + lowestId,
                    cluster.getFirst().category(),
                    cluster.size(),
                    cluster.stream().mapToLong(Member::complaintCount).sum(),
                    lat, lng, Math.round(radius),
                    maxScore, Wire.enumValue(PriorityBand.of(maxScore)),
                    Wire.timestamp(last),
                    wards.entrySet().stream()
                            .map(e -> new HotspotsDto.WardRef(Wire.id(e.getKey()), e.getValue())).toList(),
                    incidents));
        }
        hotspots.sort(Comparator.comparingDouble(HotspotsDto.Hotspot::getMaxPriorityScore).reversed()
                .thenComparing(Comparator.comparingInt(HotspotsDto.Hotspot::getIncidentCount).reversed())
                .thenComparing(HotspotsDto.Hotspot::getId));
        return hotspots;
    }

    /** Great-circle distance in metres (haversine). */
    static double metres(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
