package com.civicpulse.backend_spring.repository.spec;

import com.civicpulse.backend_spring.entity.Category;
import com.civicpulse.backend_spring.entity.CategoryAlias;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.service.priority.PriorityBand;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Composable filters for GET /incidents and the dashboard.
 *
 * An absent filter returns a Specification whose toPredicate yields NULL,
 * which Spring Data reads as "no restriction" and drops when composing.
 *
 * Note it is the PREDICATE that may be null, never the Specification itself:
 * Specification.allOf asserts its arguments are non-null and throws
 * IllegalArgumentException on a null element, so returning a bare null here
 * would turn every unfiltered request into a 500.
 *
 * Collection filters are OR within the filter; separate filters AND together
 * (docs/api-contract.md, "Shared filter parameters").
 */
public final class IncidentSpecifications {

    private IncidentSpecifications() {
    }

    public static Specification<Incident> hasWard(Long wardId) {
        return (root, query, cb) ->
                wardId == null ? null : cb.equal(root.get("ward").get("id"), wardId);
    }

    public static Specification<Incident> hasWardIn(Collection<Long> wardIds) {
        return (root, query, cb) ->
                isEmpty(wardIds) ? null : root.get("ward").get("id").in(wardIds);
    }

    /** Canonical codes — the caller canonicalises before building this. */
    public static Specification<Incident> hasCategoryIn(Collection<String> categories) {
        return (root, query, cb) ->
                isEmpty(categories) ? null : root.get("category").in(categories);
    }

    public static Specification<Incident> hasStatusIn(Collection<IncidentStatus> statuses) {
        return (root, query, cb) ->
                isEmpty(statuses) ? null : root.get("status").in(statuses);
    }

    /**
     * Any of the given bands, using the bounds PriorityBand itself classifies
     * with, so a row labelled "high" is exactly a row ?priorityBand=high finds.
     * A null score reads as 0, the same as on the wire.
     */
    public static Specification<Incident> hasBandIn(Collection<PriorityBand> bands) {
        return (root, query, cb) -> {
            if (isEmpty(bands)) {
                return null;
            }
            Expression<Double> score = cb.coalesce(root.<Double>get("priorityScore"), 0.0);
            List<Predicate> any = new ArrayList<>();
            for (PriorityBand band : bands) {
                List<Predicate> within = new ArrayList<>();
                if (Double.isFinite(band.lowerBound())) {
                    within.add(cb.greaterThanOrEqualTo(score, band.lowerBound()));
                }
                if (Double.isFinite(band.upperBound())) {
                    within.add(cb.lessThan(score, band.upperBound()));
                }
                any.add(cb.and(within.toArray(Predicate[]::new)));
            }
            return cb.or(any.toArray(Predicate[]::new));
        };
    }

    public static Specification<Incident> minPriority(Double minPriority) {
        return (root, query, cb) ->
                minPriority == null ? null : cb.greaterThanOrEqualTo(root.get("priorityScore"), minPriority);
    }

    /**
     * Incidents with at least one member REPORTED in {@code [from, to)}.
     *
     * Report time, not the incident's own created_at: an operator asking about
     * last week means problems reported last week. An EXISTS rather than a
     * join, so an incident with three matching members is still one row and the
     * page count stays right.
     */
    public static Specification<Incident> hasMemberReportedBetween(LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            if (from == null && to == null) {
                return null;
            }
            Subquery<Long> members = query.subquery(Long.class);
            Root<Complaint> complaint = members.from(Complaint.class);
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(complaint.get("incident"), root));
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(complaint.get("reportedAt"), from));
            }
            if (to != null) {
                where.add(cb.lessThan(complaint.get("reportedAt"), to));
            }
            members.select(cb.literal(1L)).where(where.toArray(Predicate[]::new));
            return cb.exists(members);
        };
    }

    /**
     * Search over everything an officer can see of an incident, not just its
     * title and summary: a resident's report carries no title and only an
     * officer writes a summary, so those two alone left almost every incident
     * unfindable.
     *
     * <p>Each word of the query must match (AND), each in any of: title,
     * summary, address, category (code, officer name, resident label or a
     * registered alias), or the description,
     * title or reference number of a report in the incident. A word matches at
     * the START of a word — "pot" finds "pothole", "ole" does not — which is
     * what keeps search-as-you-type results related rather than incidental.
     *
     * <p>LIKE wildcards in the input are escaped, so searching "100%" means the
     * text "100%", not "anything starting with 100".
     */
    public static Specification<Incident> matchesText(String text) {
        return (root, query, cb) -> {
            List<String> words = searchWords(text);
            if (words.isEmpty()) {
                return null;
            }
            List<Predicate> all = new ArrayList<>();
            for (String word : words) {
                String escaped = escapeLike(word);
                String atStart = escaped + "%";
                String afterSpace = "% " + escaped + "%";

                // The officer name ("Solid waste") is what the queue shows;
                // the resident label and aliases are what people also call it.
                Subquery<Long> categories = query.subquery(Long.class);
                Root<Category> category = categories.from(Category.class);
                categories.select(cb.literal(1L)).where(
                        cb.equal(category.get("code"), root.get("category")),
                        cb.or(
                                startsWord(cb, cb.lower(category.get("name")), atStart, afterSpace),
                                startsWord(cb, cb.lower(category.get("displayName")), atStart, afterSpace)));

                Subquery<Long> aliases = query.subquery(Long.class);
                Root<CategoryAlias> alias = aliases.from(CategoryAlias.class);
                aliases.select(cb.literal(1L)).where(
                        cb.equal(alias.get("category").get("code"), root.get("category")),
                        startsWord(cb, cb.lower(alias.get("alias")), atStart, afterSpace));

                Subquery<Long> members = query.subquery(Long.class);
                Root<Complaint> complaint = members.from(Complaint.class);
                members.select(cb.literal(1L)).where(
                        cb.equal(complaint.get("incident"), root),
                        cb.or(
                                startsWord(cb, cb.lower(complaint.get("description")), atStart, afterSpace),
                                startsWord(cb, cb.lower(complaint.get("title")), atStart, afterSpace),
                                startsWord(cb, cb.lower(complaint.get("referenceNo")), atStart, afterSpace)));

                all.add(cb.or(
                        startsWord(cb, cb.lower(root.get("title")), atStart, afterSpace),
                        startsWord(cb, cb.lower(root.get("summary")), atStart, afterSpace),
                        startsWord(cb, cb.lower(root.get("address")), atStart, afterSpace),
                        // street_light reads as "street light".
                        startsWord(cb, cb.function("replace", String.class,
                                root.get("category"), cb.literal("_"), cb.literal(" ")), atStart, afterSpace),
                        cb.exists(categories),
                        cb.exists(aliases),
                        cb.exists(members)));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }

    /** Lower-cased words, at most {@value #MAX_SEARCH_WORDS}: each one is a subquery. */
    static List<String> searchWords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.trim().toLowerCase(Locale.ROOT).split("\\s+"))
                .distinct()
                .limit(MAX_SEARCH_WORDS)
                .toList();
    }

    private static final int MAX_SEARCH_WORDS = 6;

    private static Predicate startsWord(CriteriaBuilder cb, Expression<String> field,
                                        String atStart, String afterSpace) {
        return cb.or(cb.like(field, atStart, '\\'), cb.like(field, afterSpace, '\\'));
    }

    public static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean isEmpty(Collection<?> values) {
        return values == null || values.isEmpty();
    }
}
