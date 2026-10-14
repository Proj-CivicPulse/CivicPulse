package com.civicpulse.backend_spring.service.explorer;

import com.civicpulse.backend_spring.dto.QueryParams;
import com.civicpulse.backend_spring.dto.Wire;
import com.civicpulse.backend_spring.dto.dashboard.CategoryExplorerSummaryDto;
import com.civicpulse.backend_spring.dto.dashboard.TrendDto;
import com.civicpulse.backend_spring.dto.dashboard.WardExplorerSummaryDto;
import com.civicpulse.backend_spring.entity.Category;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.CategoryRepository;
import com.civicpulse.backend_spring.repository.ExplorerRepository;
import com.civicpulse.backend_spring.repository.ExplorerRepository.BucketCount;
import com.civicpulse.backend_spring.service.category.CategoryService;
import com.civicpulse.backend_spring.service.priority.PriorityBand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Explorer's aggregate endpoints: N1 (wards), N2 (categories), N3 (trends)
 * and N4 (boundaries) in docs/api-contract.md.
 *
 * <p>Owns everything that is a decision rather than a query: canonicalising
 * category filters through the registry, the high-priority threshold (from
 * PriorityBand, never a literal), and the trend bucket list, which is computed
 * here so the 400-bucket limit is enforced before any SQL runs and empty
 * buckets come back as zeros.
 */
@Service
@RequiredArgsConstructor
public class ExplorerService {

    /** Per series. Day buckets ≈ 13 months, week buckets ≈ 7.5 years. */
    public static final int MAX_BUCKETS = 400;

    /** ~10 m at Bengaluru's latitude. Display only; assignment never uses it. */
    static final double BOUNDARY_TOLERANCE_DEGREES = 0.0001;

    private final ExplorerRepository explorerRepository;
    private final CategoryService categoryService;
    private final CategoryRepository categoryRepository;

    /** "High priority" is the HIGH band and above, by the band's own bound. */
    static double highThreshold() {
        return PriorityBand.HIGH.lowerBound();
    }

    // ── N1 ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WardExplorerSummaryDto> wardSummaries(List<String> categories, String from, String to) {
        ExplorerFilter filter = filter(null, categories, from, to);
        return explorerRepository.wardSummaries(filter, highThreshold()).stream()
                .map(row -> new WardExplorerSummaryDto(
                        Wire.id(row.wardId()), row.wardCode(), row.wardName(),
                        row.complaintCount(), row.activeIncidentCount(),
                        row.highPriorityIncidentCount(), row.dominantCategory(),
                        Wire.timestamp(row.lastActivityAt())))
                .toList();
    }

    // ── N2 ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CategoryExplorerSummaryDto> categorySummaries(List<Long> wardIds, String from, String to) {
        ExplorerFilter filter = filter(wardIds, null, from, to);
        return explorerRepository.categorySummaries(filter, highThreshold()).stream()
                .map(row -> new CategoryExplorerSummaryDto(
                        row.category(), row.complaintCount(), row.activeIncidentCount(),
                        row.highPriorityIncidentCount(), row.wardCount(),
                        row.resolvedIncidentCount(), row.medianHoursToResolve(),
                        Wire.id(row.defaultDepartmentId()),
                        Wire.timestamp(row.lastActivityAt())))
                .toList();
    }

    // ── N3 ──────────────────────────────────────────────────────────────────

    public enum Bucket {
        DAY("day"), WEEK("week");

        private final String unit;

        Bucket(String unit) {
            this.unit = unit;
        }

        public String unit() {
            return unit;
        }

        /** Must agree with Postgres date_trunc: midnight, and ISO Monday for weeks. */
        LocalDateTime truncate(LocalDateTime t) {
            LocalDateTime day = t.truncatedTo(ChronoUnit.DAYS);
            return this == DAY ? day : day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }

        LocalDateTime next(LocalDateTime start) {
            return this == DAY ? start.plusDays(1) : start.plusWeeks(1);
        }

        static Bucket parse(String value) {
            if (value == null || value.isBlank()) {
                return DAY;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "day" -> DAY;
                case "week" -> WEEK;
                default -> throw new ValidationException("bucket must be one of day, week");
            };
        }
    }

    @Transactional(readOnly = true)
    public TrendDto trends(String from, String to, String bucketParam, String groupBy,
                           List<Long> wardIds, List<String> categories) {
        if (from == null || from.isBlank()) {
            throw new ValidationException("from is required");
        }
        if (to == null || to.isBlank()) {
            throw new ValidationException("to is required");
        }
        boolean byCategory = parseGroupBy(groupBy);
        Bucket bucket = Bucket.parse(bucketParam);
        ExplorerFilter filter = filter(wardIds, categories, from, to);

        List<LocalDateTime> starts = bucketStarts(bucket, filter.from(), filter.to());

        Map<String, Map<LocalDateTime, Long>> counts = new HashMap<>();
        for (BucketCount row : explorerRepository.complaintCounts(filter, bucket.unit(), byCategory)) {
            counts.computeIfAbsent(row.key() == null ? "" : row.key(), k -> new HashMap<>())
                    .merge(row.bucket(), row.count(), Long::sum);
        }

        List<String> keys = byCategory ? seriesKeys(counts.keySet(), filter.categories()) : List.of("");

        List<TrendDto.Series> series = new ArrayList<>();
        for (String key : keys) {
            Map<LocalDateTime, Long> byBucket = counts.getOrDefault(key, Map.of());
            List<TrendDto.Point> points = starts.stream()
                    .map(start -> new TrendDto.Point(Wire.timestamp(start), byBucket.getOrDefault(start, 0L)))
                    .toList();
            series.add(new TrendDto.Series(key.isEmpty() ? null : key, points));
        }
        return new TrendDto(bucket.unit(), series);
    }

    /**
     * Every bucket overlapping {@code [from, to)}. The first starts at
     * {@code from} truncated, so a range beginning mid-day still has its day.
     * Refused over {@link #MAX_BUCKETS} before anything is queried.
     */
    static List<LocalDateTime> bucketStarts(Bucket bucket, LocalDateTime from, LocalDateTime to) {
        List<LocalDateTime> starts = new ArrayList<>();
        for (LocalDateTime start = bucket.truncate(from); start.isBefore(to); start = bucket.next(start)) {
            if (starts.size() == MAX_BUCKETS) {
                throw new ValidationException("range is too long: at most " + MAX_BUCKETS + " "
                        + bucket.unit() + " buckets; narrow from/to or use a larger bucket");
            }
            starts.add(start);
        }
        return starts;
    }

    private static boolean parseGroupBy(String value) {
        if (value == null || value.isBlank() || value.trim().equalsIgnoreCase("none")) {
            return false;
        }
        if (value.trim().equalsIgnoreCase("category")) {
            return true;
        }
        throw new ValidationException("groupBy must be one of none, category");
    }

    /**
     * Series for every category with data, plus every category explicitly
     * filtered on — a chart of a selected category shows its zeros instead of
     * silently missing. Registry order, unknown codes last.
     */
    private List<String> seriesKeys(Set<String> withData, List<String> filtered) {
        Set<String> wanted = new LinkedHashSet<>(withData);
        wanted.addAll(filtered);
        List<String> ordered = new ArrayList<>();
        for (Category category : categoryRepository.findAllByOrderBySortOrderAscCodeAsc()) {
            if (wanted.remove(category.getCode())) {
                ordered.add(category.getCode());
            }
        }
        wanted.stream().sorted().forEach(ordered::add);
        return ordered;
    }

    // ── N4 ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String wardBoundaries() {
        return explorerRepository.wardBoundariesGeoJson(BOUNDARY_TOLERANCE_DEGREES);
    }

    // ── Shared ──────────────────────────────────────────────────────────────

    /** Parses the shared filters, canonicalising categories through the registry. */
    ExplorerFilter filter(List<Long> wardIds, List<String> categories, String from, String to) {
        List<String> canonical = QueryParams.each(categories, categoryService::canonicaliseFilter);
        return new ExplorerFilter(
                wardIds == null ? List.of() : wardIds.stream().distinct().toList(),
                canonical,
                QueryParams.instant(from, "from"),
                QueryParams.instant(to, "to"));
    }
}
