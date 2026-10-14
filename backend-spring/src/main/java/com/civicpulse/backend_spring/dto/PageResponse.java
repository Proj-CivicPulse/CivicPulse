package com.civicpulse.backend_spring.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * The list envelope every Explorer list endpoint returns
 * (docs/api-contract.md, "Lists: pagination, sorting, size").
 *
 * {@code total} is the full match count, so a client can say "showing 200 of
 * 1,340" instead of implying the page is everything. {@code limit} is echoed
 * AFTER clamping, so a client that asked for 1,000 can see it got 200.
 */
@Getter
@AllArgsConstructor
public class PageResponse<T> {

    private final List<T> items;
    private final long total;
    private final int limit;
    private final int offset;
}
