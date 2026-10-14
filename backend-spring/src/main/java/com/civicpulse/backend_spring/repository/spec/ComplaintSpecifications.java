package com.civicpulse.backend_spring.repository.spec;

import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.enums.ComplaintStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Locale;

/**
 * Composable filters for the officer complaint list (GET /complaints, E4).
 * Same conventions as {@link IncidentSpecifications}: an absent filter yields a
 * null predicate, collections are OR within and AND across.
 */
public final class ComplaintSpecifications {

    private ComplaintSpecifications() {
    }

    public static Specification<Complaint> hasWardIn(Collection<Long> wardIds) {
        return (root, query, cb) ->
                wardIds == null || wardIds.isEmpty() ? null : root.get("ward").get("id").in(wardIds);
    }

    /** Canonical codes — the caller canonicalises first. */
    public static Specification<Complaint> hasCategoryIn(Collection<String> categories) {
        return (root, query, cb) ->
                categories == null || categories.isEmpty() ? null : root.get("category").in(categories);
    }

    public static Specification<Complaint> hasStatusIn(Collection<ComplaintStatus> statuses) {
        return (root, query, cb) ->
                statuses == null || statuses.isEmpty() ? null : root.get("status").in(statuses);
    }

    /** Reported in {@code [from, to)} — report time, never row creation. */
    public static Specification<Complaint> reportedBetween(LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            if (from == null && to == null) {
                return null;
            }
            if (from == null) {
                return cb.lessThan(root.get("reportedAt"), to);
            }
            if (to == null) {
                return cb.greaterThanOrEqualTo(root.get("reportedAt"), from);
            }
            return cb.and(cb.greaterThanOrEqualTo(root.get("reportedAt"), from),
                    cb.lessThan(root.get("reportedAt"), to));
        };
    }

    /** Case-insensitive substring over title and description, wildcards escaped. */
    public static Specification<Complaint> matchesText(String text) {
        return (root, query, cb) -> {
            if (text == null || text.isBlank()) {
                return null;
            }
            String pattern = "%" + IncidentSpecifications.escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("title")), pattern, '\\'),
                    cb.like(cb.lower(root.get("description")), pattern, '\\'));
        };
    }
}
