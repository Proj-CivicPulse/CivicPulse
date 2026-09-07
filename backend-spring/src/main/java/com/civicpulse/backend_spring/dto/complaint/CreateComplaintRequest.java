package com.civicpulse.backend_spring.dto.complaint;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateComplaintRequest {

    @Size(max = 255)
    private String title;

    @NotBlank
    @Size(max = 5000)
    private String description;

    @NotBlank
    @Size(max = 255)
    private String category;

    /**
     * Optional. When present it wins — an explicit choice by the person
     * reporting. When absent the server derives it from lat/long via
     * WardResolver, which is the path the submit form uses: we already demand
     * coordinates, so making the reporter also name their ward wastes them.
     *
     * If neither yields a ward, the request is rejected rather than guessed at.
     */
    private String wardId;

    @NotNull
    @DecimalMin("-90")
    @DecimalMax("90")
    private Double lat;

    @NotNull
    @DecimalMin("-180")
    @DecimalMax("180")
    @JsonProperty("long")
    private Double lng;

    @Size(max = 1024)
    private String photoUrl;
}
