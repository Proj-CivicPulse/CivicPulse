package com.civicpulse.backend_spring.service.category;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The normaliser is the contract between the alias rows seeded in
 * V11__category_registry.sql and every value that arrives at runtime. If the
 * two transforms disagree, no alias ever resolves and every submission is
 * rejected as an unknown category — a total outage of the submit form that no
 * other test would catch, since each half works perfectly on its own.
 */
class CategoryNormalizerTest {

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource({
            // Case is the commonest collision in the audit's examples.
            "Garbage,                garbage",
            "GARBAGE,                garbage",
            // Separators of every kind collapse to one space.
            "Street-Light,           street light",
            "street_light,           street light",
            "Street  Light,          street light",
            "'  Street Light  ',     street light",
            "Street/Light,           street light",
            // Punctuation a person actually types.
            "'Garbage, not collected', garbage not collected",
            "Water (supply),         water supply",
            // Digits survive; they can be meaningful in an external code.
            "Ward17 Drain,           ward17 drain",
    })
    @DisplayName("folds case, separators and padding onto one spelling")
    void normalises(String raw, String expected) {
        assertThat(CategoryNormalizer.normalise(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("strips accents so a pasted value still resolves")
    void foldsAccents() {
        assertThat(CategoryNormalizer.normalise("Drainagé")).isEqualTo("drainage");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "---", "!!!", "\t\n"})
    @DisplayName("malformed input normalises to empty rather than to a lookup key")
    void malformedBecomesEmpty(String raw) {
        // Empty is what CategoryService treats as unresolvable. It must never
        // become a key that could collide with a real alias row.
        assertThat(CategoryNormalizer.normalise(raw)).isEmpty();
    }

    @Test
    @DisplayName("null is empty, not an exception")
    void nullIsEmpty() {
        assertThat(CategoryNormalizer.normalise(null)).isEmpty();
    }

    @Test
    @DisplayName("is idempotent — normalising a stored alias changes nothing")
    void idempotent() {
        // Aliases are STORED already normalised. Anything else would mean the
        // seeded rows drift out of reach of their own lookup.
        for (String alias : new String[] {
                "street light", "solid waste", "blocked drain", "pothole", "water supply"}) {
            assertThat(CategoryNormalizer.normalise(alias)).isEqualTo(alias);
        }
    }
}
