package com.civicpulse.backend_spring.service.category;

import com.civicpulse.backend_spring.dto.category.CategoryDto;
import com.civicpulse.backend_spring.entity.Category;
import com.civicpulse.backend_spring.entity.CategoryAlias;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.CategoryAliasRepository;
import com.civicpulse.backend_spring.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The category registry: the single authority over what a category may be.
 *
 * <p>Every write path funnels a caller-supplied string through
 * {@link #require(String)} before it reaches the database, so what gets stored
 * is always a canonical {@code code}. That is the whole point — downstream,
 * category is compared with exact equality (backend-node's candidate
 * pre-filter, the naive fallback, the dashboard breakdown), so the alternative
 * to normalising on the way in is fuzzy matching in four different places that
 * each get it slightly differently wrong.
 *
 * <p>Reads hit the database every call rather than caching. The table is a
 * handful of rows behind a primary-key/unique-index lookup, so a cache would
 * buy nothing measurable and cost a staleness bug the first time someone adds
 * an alias — the same reasoning as
 * {@link com.civicpulse.backend_spring.service.ward.CentroidWardResolver}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryAliasRepository aliasRepository;

    /** Active categories in submit-form order. Serves GET /categories. */
    @Transactional(readOnly = true)
    public List<CategoryDto> listActive() {
        return categoryRepository.findByActiveTrueOrderBySortOrderAscCodeAsc().stream()
                .map(CategoryDto::from)
                .toList();
    }

    /**
     * Resolves any spelling to its canonical category, from our own vocabulary.
     *
     * @return empty when nothing in the registry claims that spelling — which
     *         is a real answer ("we do not know this category"), not a failure
     */
    @Transactional(readOnly = true)
    public Optional<Category> resolve(String raw) {
        return resolve(raw, CategoryAlias.SOURCE_INTERNAL);
    }

    /**
     * Resolves a spelling within a named vocabulary.
     *
     * @param source the vocabulary the value came from — {@code internal} for
     *               our UI and public API, or an external feed's own source key
     *               so its terms cannot leak into ours
     */
    @Transactional(readOnly = true)
    public Optional<Category> resolve(String raw, String source) {
        String normalised = CategoryNormalizer.normalise(raw);
        if (normalised.isEmpty()) {
            return Optional.empty();
        }
        return aliasRepository.findCategoryByAlias(source, normalised);
    }

    /**
     * Resolves, or rejects the submission.
     *
     * <p>Rejecting is deliberate, and it is a behaviour change: before the
     * registry existed, any string at all was accepted and stored verbatim.
     * Accepting an unknown category is not neutral — it creates a value that
     * can only ever group with an identical typo, so the report is filed but
     * invisible to everything that aggregates. A 400 naming the accepted codes
     * is the honest answer, and the frontend never triggers it because it
     * submits codes read from this same registry.
     *
     * @throws ValidationException when the value is unknown or the category has
     *                             been retired
     */
    @Transactional(readOnly = true)
    public Category require(String raw) {
        Category category = resolve(raw).orElseThrow(() -> {
            log.info("Rejected unknown category {} (normalised to '{}')",
                    raw, CategoryNormalizer.normalise(raw));
            return new ValidationException(
                    "category must be one of: " + String.join(", ", activeCodes()));
        });

        if (!category.isActive()) {
            throw new ValidationException(
                    "category '" + category.getCode() + "' is no longer accepted; use one of: "
                            + String.join(", ", activeCodes()));
        }
        return category;
    }

    /**
     * The filter-side counterpart of {@link #require(String)}: best-effort
     * canonicalisation for a READ.
     *
     * <p>A filter must not 400 on an unrecognised value the way a write does.
     * Rows predating the registry can still hold a non-canonical category the
     * V11 backfill left alone, and an officer filtering on exactly that value
     * should see exactly those rows. So an unresolvable value is passed through
     * untouched — it simply matches whatever literally carries it.
     *
     * @return the canonical code when the registry knows the spelling, the
     *         trimmed input otherwise, null for null/blank
     */
    @Transactional(readOnly = true)
    public String canonicaliseFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return resolve(raw).map(Category::getCode).orElseGet(raw::trim);
    }

    private List<String> activeCodes() {
        return categoryRepository.findByActiveTrueOrderBySortOrderAscCodeAsc().stream()
                .map(Category::getCode)
                .toList();
    }
}
