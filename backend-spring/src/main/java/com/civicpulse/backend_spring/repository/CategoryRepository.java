package com.civicpulse.backend_spring.repository;

import com.civicpulse.backend_spring.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByCode(String code);

    /** Submit-form order: explicit sort_order first, then code for a stable tie-break. */
    List<Category> findByActiveTrueOrderBySortOrderAscCodeAsc();

    List<Category> findAllByOrderBySortOrderAscCodeAsc();
}
