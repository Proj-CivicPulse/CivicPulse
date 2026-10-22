package com.civicpulse.backend_spring.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Every {@code LocalDateTime} attribute is a UTC instant stored in a
 * TIMESTAMPTZ column (V23).
 *
 * <p>The Java side keeps LocalDateTime — it is what every service, query and
 * test already speaks, and its values are UTC because the JVM is pinned to UTC
 * (BackendSpringApplication). This converter is where that convention meets
 * the database: it states the offset explicitly on the way in and normalises
 * to UTC on the way out, so the stored value is an unambiguous instant for
 * every other client too (backend-node, psql, a BI tool).
 */
@Converter(autoApply = true)
public class UtcTimestampConverter implements AttributeConverter<LocalDateTime, OffsetDateTime> {

    @Override
    public OffsetDateTime convertToDatabaseColumn(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    @Override
    public LocalDateTime convertToEntityAttribute(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
