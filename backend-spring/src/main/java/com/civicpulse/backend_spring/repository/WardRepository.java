package com.civicpulse.backend_spring.repository;

import com.civicpulse.backend_spring.entity.Ward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WardRepository extends JpaRepository<Ward, Long> {

    /** Stable ordering, so the landing strip does not reshuffle between loads. */
    List<Ward> findAllByOrderByIdAsc();
}
