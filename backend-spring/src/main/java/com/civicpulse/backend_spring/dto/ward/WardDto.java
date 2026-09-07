package com.civicpulse.backend_spring.dto.ward;

import com.civicpulse.backend_spring.dto.Wire;
import com.civicpulse.backend_spring.entity.Ward;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Public municipal reference data. Serves GET /wards, /wards/{id},
 * /wards/resolve, and the internal ward lookup.
 */
@Getter
@AllArgsConstructor
public class WardDto {

    private final String id;
    private final String code;
    private final String name;
    private final String zone;
    private final Double lat;

    // "long" is a Java keyword, so the field cannot be named for the wire.
    @JsonProperty("long")
    private final Double lng;

    public static WardDto from(Ward ward) {
        return new WardDto(
                Wire.id(ward.getId()),
                ward.getCode(),
                ward.getName(),
                ward.getZone(),
                ward.getLatitude(),
                ward.getLongitude()
        );
    }
}
