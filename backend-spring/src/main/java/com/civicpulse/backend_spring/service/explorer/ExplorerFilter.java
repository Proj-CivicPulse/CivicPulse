package com.civicpulse.backend_spring.service.explorer;

import com.civicpulse.backend_spring.dto.QueryParams;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The shared filters an Explorer aggregate accepts (docs/api-contract.md,
 * "Shared filter parameters"). Each endpoint documents which subset it honours.
 *
 * <p>{@code categories} must already be canonical codes — the service
 * canonicalises through the registry before building one, so the repository
 * never sees an alias. {@code from}/{@code to} bound {@code reported_at} as a
 * half-open window {@code [from, to)}.
 */
public record ExplorerFilter(
        List<Long> wardIds,
        List<String> categories,
        LocalDateTime from,
        LocalDateTime to) {

    public ExplorerFilter {
        wardIds = wardIds == null ? List.of() : List.copyOf(wardIds);
        categories = categories == null ? List.of() : List.copyOf(categories);
        QueryParams.requireOrderedWindow(from, to);
    }

    public static ExplorerFilter none() {
        return new ExplorerFilter(List.of(), List.of(), null, null);
    }

    public boolean hasWindow() {
        return from != null || to != null;
    }
}
