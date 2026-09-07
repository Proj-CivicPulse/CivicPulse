package com.civicpulse.backend_spring.repository.spec;

import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable filters for GET /incidents.
 *
 * An absent filter returns a Specification whose toPredicate yields NULL,
 * which Spring Data reads as "no restriction" and drops when composing.
 *
 * Note it is the PREDICATE that may be null, never the Specification itself:
 * Specification.allOf asserts its arguments are non-null and throws
 * IllegalArgumentException on a null element, so returning a bare null here
 * would turn every unfiltered request into a 500.
 *
 * This is what keeps four optional filters from becoming sixteen derived query
 * methods — and Query By Example could not express minPriority, a >=
 * comparison, at all.
 */
public final class IncidentSpecifications {

    private IncidentSpecifications() {
    }

    public static Specification<Incident> hasWard(Long wardId) {
        return (root, query, cb) ->
                wardId == null ? null : cb.equal(root.get("ward").get("id"), wardId);
    }

    public static Specification<Incident> hasCategory(String category) {
        return (root, query, cb) ->
                category == null || category.isBlank() ? null : cb.equal(root.get("category"), category);
    }

    public static Specification<Incident> hasStatus(IncidentStatus status) {
        return (root, query, cb) ->
                status == null ? null : cb.equal(root.get("status"), status);
    }

    public static Specification<Incident> minPriority(Double minPriority) {
        return (root, query, cb) ->
                minPriority == null ? null : cb.greaterThanOrEqualTo(root.get("priorityScore"), minPriority);
    }
}
