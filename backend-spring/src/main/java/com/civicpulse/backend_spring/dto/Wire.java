package com.civicpulse.backend_spring.dto;

import com.civicpulse.backend_spring.exception.ValidationException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Conversions at the DTO boundary, so the wire contract is applied in exactly
 * one place (docs/api-contract.md):
 *
 *   - ids are STRINGS, always, so a later move to UUIDs does not break clients
 *   - enums are lowercase on the wire, uppercase in Java
 *   - timestamps are ISO-8601 UTC with an explicit trailing Z
 */
public final class Wire {

    private Wire() {
    }

    public static String id(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    public static String enumValue(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Entities store LocalDateTime in zoneless TIMESTAMP(6) columns, which
     * Jackson would otherwise serialise without an offset. A browser parses
     * that as LOCAL time, shifting every timestamp by the viewer's offset —
     * 5h30m for this application's likely audience. Stamping Z is what makes
     * the value unambiguous.
     *
     * The columns hold UTC because the JVM default zone is forced to UTC at
     * startup (see BackendSpringApplication).
     */
    public static String timestamp(LocalDateTime value) {
        return value == null
                ? null
                : value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
    }

    /**
     * Parses a lowercase wire enum, raising 400 rather than 500 when a query
     * parameter carries something that is not a member.
     */
    public static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(field + " must be one of " + allowedValues(type));
        }
    }

    private static <E extends Enum<E>> String allowedValues(Class<E> type) {
        StringBuilder builder = new StringBuilder();
        for (E constant : type.getEnumConstants()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(constant.name().toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }
}
