package com.civicpulse.backend_spring.service.explorer;

import com.civicpulse.backend_spring.dto.dashboard.TrendDto;
import com.civicpulse.backend_spring.entity.Category;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.CategoryRepository;
import com.civicpulse.backend_spring.repository.ExplorerRepository;
import com.civicpulse.backend_spring.repository.ExplorerRepository.BucketCount;
import com.civicpulse.backend_spring.service.category.CategoryService;
import com.civicpulse.backend_spring.service.priority.PriorityBand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The decisions ExplorerService owns: buckets, zero-fill, parameter rules, the threshold. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExplorerServiceTest {

    @Mock private ExplorerRepository explorerRepository;
    @Mock private CategoryService categoryService;
    @Mock private CategoryRepository categoryRepository;

    private ExplorerService service;

    @BeforeEach
    void setUp() {
        service = new ExplorerService(explorerRepository, categoryService, categoryRepository);
        when(categoryService.canonicaliseFilter(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(categoryRepository.findAllByOrderBySortOrderAscCodeAsc()).thenReturn(List.of(
                Category.builder().code("pothole").sortOrder(10).build(),
                Category.builder().code("garbage").sortOrder(30).build()));
    }

    @Test
    @DisplayName("high priority is PriorityBand.HIGH's own lower bound, not a literal")
    void highThresholdComesFromTheBand() {
        assertThat(ExplorerService.highThreshold()).isEqualTo(PriorityBand.HIGH.lowerBound());
    }

    @Test
    @DisplayName("day buckets start at midnight, cover a range starting mid-day, exclude `to`")
    void dayBuckets() {
        List<LocalDateTime> starts = ExplorerService.bucketStarts(ExplorerService.Bucket.DAY,
                LocalDateTime.of(2026, 10, 1, 15, 0), LocalDateTime.of(2026, 10, 4, 0, 0));

        assertThat(starts).containsExactly(
                LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 10, 2, 0, 0),
                LocalDateTime.of(2026, 10, 3, 0, 0));
    }

    @Test
    @DisplayName("week buckets start on Monday, matching Postgres date_trunc('week')")
    void weekBucketsStartMonday() {
        // 2026-10-14 is a Wednesday; its week began Monday 2026-10-12.
        List<LocalDateTime> starts = ExplorerService.bucketStarts(ExplorerService.Bucket.WEEK,
                LocalDateTime.of(2026, 10, 14, 9, 0), LocalDateTime.of(2026, 10, 27, 0, 0));

        assertThat(starts).containsExactly(
                LocalDateTime.of(2026, 10, 12, 0, 0),
                LocalDateTime.of(2026, 10, 19, 0, 0),
                LocalDateTime.of(2026, 10, 26, 0, 0));
    }

    @Test
    @DisplayName("more than 400 buckets is refused BEFORE any query runs")
    void bucketLimitCheckedFirst() {
        assertThatThrownBy(() -> service.trends("2024-01-01T00:00:00Z", "2026-01-01T00:00:00Z",
                "day", null, null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("400");
        verify(explorerRepository, never()).complaintCounts(any(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("from and to are required, bucket and groupBy are validated")
    void trendParameters() {
        assertThatThrownBy(() -> service.trends(null, "2026-10-08T00:00:00Z", null, null, null, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("from");
        assertThatThrownBy(() -> service.trends("2026-10-01T00:00:00Z", null, null, null, null, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("to");
        assertThatThrownBy(() -> service.trends("2026-10-01T00:00:00Z", "2026-10-08T00:00:00Z",
                "month", null, null, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("bucket");
        assertThatThrownBy(() -> service.trends("2026-10-01T00:00:00Z", "2026-10-08T00:00:00Z",
                null, "ward", null, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("groupBy");
    }

    @Test
    @DisplayName("every bucket is present with zeros; series follow registry order")
    void zeroFilledSeriesInRegistryOrder() {
        when(explorerRepository.complaintCounts(any(), anyString(), anyBoolean())).thenReturn(List.of(
                new BucketCount(LocalDateTime.of(2026, 10, 2, 0, 0), "garbage", 3),
                new BucketCount(LocalDateTime.of(2026, 10, 1, 0, 0), "pothole", 1)));

        TrendDto trend = service.trends("2026-10-01T00:00:00Z", "2026-10-04T00:00:00Z",
                "day", "category", null, null);

        assertThat(trend.getBucket()).isEqualTo("day");
        assertThat(trend.getSeries()).extracting(TrendDto.Series::getKey)
                .containsExactly("pothole", "garbage");
        assertThat(trend.getSeries().get(1).getPoints())
                .extracting(TrendDto.Point::getStart, TrendDto.Point::getCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("2026-10-01T00:00:00Z", 0L),
                        org.assertj.core.groups.Tuple.tuple("2026-10-02T00:00:00Z", 3L),
                        org.assertj.core.groups.Tuple.tuple("2026-10-03T00:00:00Z", 0L));
    }

    @Test
    @DisplayName("a filtered category with no data still gets a series of zeros")
    void filteredCategoryShowsZeros() {
        when(explorerRepository.complaintCounts(any(), anyString(), anyBoolean())).thenReturn(List.of());

        TrendDto trend = service.trends("2026-10-01T00:00:00Z", "2026-10-03T00:00:00Z",
                "day", "category", null, List.of("garbage"));

        assertThat(trend.getSeries()).singleElement().satisfies(s -> {
            assertThat(s.getKey()).isEqualTo("garbage");
            assertThat(s.getPoints()).extracting(TrendDto.Point::getCount).containsExactly(0L, 0L);
        });
    }

    @Test
    @DisplayName("ungrouped trend is one series with a null key")
    void ungroupedSeries() {
        when(explorerRepository.complaintCounts(any(), anyString(), anyBoolean())).thenReturn(List.of(
                new BucketCount(LocalDateTime.of(2026, 10, 1, 0, 0), null, 5)));

        TrendDto trend = service.trends("2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z",
                null, null, null, null);

        assertThat(trend.getSeries()).singleElement().satisfies(s -> {
            assertThat(s.getKey()).isNull();
            assertThat(s.getPoints()).extracting(TrendDto.Point::getCount).containsExactly(5L);
        });
    }
}
