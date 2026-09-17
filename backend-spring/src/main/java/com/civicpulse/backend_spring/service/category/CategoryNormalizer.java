package com.civicpulse.backend_spring.service.category;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The one definition of "the same category spelling".
 *
 * <p>The transform is: strip accents, lowercase, collapse every run of
 * non-alphanumeric characters to a single space, trim. So {@code "Street-Light"},
 * {@code "street_light"}, {@code "  STREET   LIGHT "} and {@code "Street Light"}
 * all become {@code "street light"}, which is then a plain equality lookup
 * against {@code category_aliases.alias}.
 *
 * <p>It must agree with the expression V11__category_registry.sql uses to seed
 * and backfill — {@code btrim(regexp_replace(lower(x), '[^a-z0-9]+', ' ', 'g'))}
 * — on every alias row the migration writes. If the two ever drift, aliases
 * stop resolving and every submission silently becomes "unknown category", so
 * the round-trip is asserted in CategoryNormalizerTest.
 *
 * <p>The one deliberate difference: this folds accents first, which the SQL
 * does not. That makes it a SUPERSET — anything SQL resolves, this resolves
 * identically, and this additionally rescues an accented paste. It matters only
 * in the direction that cannot break lookup, because the stored aliases are
 * plain ASCII either way.
 *
 * <p>Deliberately NOT fuzzy. Edit distance or stemming here would let
 * "water" reach "waterlogging", which is a different department's problem.
 * Anything that should match gets an explicit alias row instead, reviewed by a
 * person.
 */
public final class CategoryNormalizer {

    private CategoryNormalizer() {
    }

    /** @return the normalised form, or an empty string for null/blank input */
    public static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        // NFD then dropping combining marks folds "Straße"-style accents into
        // ASCII, so an accented paste from a PDF still resolves.
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");

        return folded.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }
}
