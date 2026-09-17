package com.civicpulse.backend_spring.repository;

import com.civicpulse.backend_spring.entity.Ward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WardRepository extends JpaRepository<Ward, Long> {

    /** Stable ordering, so the landing strip does not reshuffle between loads. */
    List<Ward> findAllByOrderByIdAsc();

    /**
     * Selectable wards. Retired ones — the V2 placeholders — stay readable by id
     * because complaints reference them, but must never appear in a picker or a
     * ward strip.
     */
    List<Ward> findByActiveTrueOrderByIdAsc();

    /**
     * The ward whose boundary contains a point.
     *
     * <p>A NATIVE query because {@code boundary} is deliberately unmapped: the
     * only thing Java ever asks of the polygon is containment, and PostGIS
     * answers that through a GiST index. Mapping the geometry would drag
     * hibernate-spatial and JTS into the build to express a question we never
     * need to ask in Java.
     *
     * <p>Note the argument order: {@code ST_MakePoint} takes X then Y, which is
     * LONGITUDE then latitude — the reverse of how every caller in this codebase
     * says it. Getting it backwards yields a point in the Indian Ocean and a
     * silent 404, so the parameters are named rather than positional.
     *
     * <p>{@code LIMIT 1} because BBMP wards tile the city without overlap; on a
     * shared boundary line PostGIS may report both, and either is defensible.
     */
    @Query(value = """
            SELECT * FROM wards w
            WHERE w.active
              AND w.boundary IS NOT NULL
              AND ST_Contains(w.boundary, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326))
            ORDER BY w.id
            LIMIT 1
            """, nativeQuery = true)
    Optional<Ward> findContaining(@Param("lat") double latitude, @Param("lng") double longitude);
}
