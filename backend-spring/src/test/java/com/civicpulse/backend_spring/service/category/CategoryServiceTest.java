package com.civicpulse.backend_spring.service.category;

import com.civicpulse.backend_spring.entity.Category;
import com.civicpulse.backend_spring.entity.CategoryAlias;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.CategoryAliasRepository;
import com.civicpulse.backend_spring.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Category resolution — the fix for the fragmentation the audit recorded, where
 * "Garbage", "solid waste" and "Uncollected Garbage" became three incidents
 * that could never merge because every consumer compares category with exact
 * equality.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CategoryServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryAliasRepository aliasRepository;

    private CategoryService service;

    private Category garbage;
    private Category retired;

    @BeforeEach
    void setUp() {
        service = new CategoryService(categoryRepository, aliasRepository);

        garbage = Category.builder()
                .id(3L).code("garbage").name("Solid waste")
                .displayName("Garbage not collected").active(true).sortOrder(30)
                .build();
        retired = Category.builder()
                .id(9L).code("legacy").name("Legacy").displayName("Legacy")
                .active(false).sortOrder(99)
                .build();

        // The seeded aliases, keyed the way the table stores them: normalised.
        stubAlias("garbage", garbage);
        stubAlias("solid waste", garbage);
        stubAlias("uncollected garbage", garbage);
        stubAlias("legacy", retired);

        when(categoryRepository.findByActiveTrueOrderBySortOrderAscCodeAsc())
                .thenReturn(List.of(garbage));
    }

    private void stubAlias(String alias, Category category) {
        when(aliasRepository.findCategoryByAlias(eq(CategoryAlias.SOURCE_INTERNAL), eq(alias)))
                .thenReturn(Optional.of(category));
    }

    @Test
    @DisplayName("every audited spelling of one problem resolves to one code")
    void aliasesCollapse() {
        // This is the whole point: four inputs, one stored value, so the
        // matcher's ward+category pre-filter sees them as one candidate pool.
        for (String spelling : new String[] {
                "Garbage", "garbage", "Solid Waste", "  UNCOLLECTED   garbage "}) {
            assertThat(service.resolve(spelling))
                    .as("resolving %s", spelling)
                    .contains(garbage);
        }
    }

    @Test
    @DisplayName("an unknown spelling resolves to empty rather than inventing a category")
    void unknownResolvesEmpty() {
        assertThat(service.resolve("interpretive dance")).isEmpty();
    }

    @Test
    @DisplayName("require() stores the canonical code, not what the caller sent")
    void requireReturnsCanonical() {
        assertThat(service.require("Solid Waste").getCode()).isEqualTo("garbage");
    }

    @Test
    @DisplayName("require() rejects an unknown category and names the accepted codes")
    void requireRejectsUnknown() {
        // Rejecting is the behaviour change. Storing it would create a value
        // that can only ever group with an identical typo.
        assertThatThrownBy(() -> service.require("interpretive dance"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("garbage");
    }

    @Test
    @DisplayName("require() rejects a retired category even though it still resolves")
    void requireRejectsInactive() {
        // It must still RESOLVE — old complaints reference it and have to keep
        // rendering — but it may not be chosen for something new.
        assertThat(service.resolve("legacy")).contains(retired);
        assertThatThrownBy(() -> service.require("legacy"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no longer accepted");
    }

    @Test
    @DisplayName("require() rejects blank and null rather than storing an empty category")
    void requireRejectsBlank() {
        assertThatThrownBy(() -> service.require("   ")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.require(null)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("a filter canonicalises when it can and passes through when it cannot")
    void filterCanonicalisation() {
        // Canonical: an officer filtering on any spelling finds the stored rows.
        assertThat(service.canonicaliseFilter("Solid Waste")).isEqualTo("garbage");

        // Pass-through: rows the V11 backfill could not map still literally
        // carry their original value, and filtering on it must find them —
        // which is why this does not throw the way require() does.
        assertThat(service.canonicaliseFilter("Legacy Import Value"))
                .isEqualTo("Legacy Import Value");

        assertThat(service.canonicaliseFilter(null)).isNull();
        assertThat(service.canonicaliseFilter("  ")).isNull();
    }

    @Test
    @DisplayName("an external vocabulary cannot resolve against internal aliases")
    void sourcesAreIsolated() {
        // A feed's term for something is scoped to that feed. Letting it fall
        // back to ours is how an external word quietly takes over a category.
        when(aliasRepository.findCategoryByAlias(eq("bbmp"), eq("garbage")))
                .thenReturn(Optional.empty());

        assertThat(service.resolve("Garbage", "bbmp")).isEmpty();
    }
}
