package com.civicpulse.backend_spring.repository;

import com.civicpulse.backend_spring.entity.Category;
import com.civicpulse.backend_spring.entity.CategoryAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryAliasRepository extends JpaRepository<CategoryAlias, Long> {

    /**
     * Resolves one already-normalised spelling within one vocabulary.
     *
     * <p>Joins the category eagerly: the caller always wants it, and the alias
     * association is LAZY, so without this the lookup pays a second query on an
     * entity that is about to be detached anyway.
     */
    @Query("""
            select a.category from CategoryAlias a
            where a.source = :source and a.alias = :alias
            """)
    Optional<Category> findCategoryByAlias(
            @Param("source") String source, @Param("alias") String alias);

    List<CategoryAlias> findByCategoryOrderByAliasAsc(Category category);
}
