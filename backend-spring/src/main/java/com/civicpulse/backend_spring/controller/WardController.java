package com.civicpulse.backend_spring.controller;

import com.civicpulse.backend_spring.dto.ward.WardDto;
import com.civicpulse.backend_spring.dto.ward.WardSummaryDto;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.service.ward.WardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Public municipal reference data. The whole /wards prefix is readable without
 * a session (GET only — see SecurityConfig).
 */
@RestController
@RequestMapping("/wards")
@RequiredArgsConstructor
public class WardController {

    private final WardService wardService;

    @GetMapping
    public ResponseEntity<List<WardDto>> list() {
        return ResponseEntity.ok(wardService.list());
    }

    /**
     * Ward name plus open-incident count, for the public landing page.
     *
     * COUNTS, NEVER ROWS. This is the one unauthenticated endpoint that
     * touches incident data, and it must stay an aggregate.
     *
     * What is deliberately absent, and why it must stay absent:
     *
     *   - incident titles and summaries — LLM-generated prose over the text of
     *     residents' complaints
     *   - coordinates — a single-complaint incident's centroid IS that
     *     complaint's address
     *   - a category breakdown — "Ward 17 has 1 open <sensitive category>
     *     incident" approaches identifying a person at count 1
     *   - priority scores — leaks the operational model
     *
     * What it does disclose is an aggregate count per ward: roughly "how many
     * distinct live problems this ward has". That cannot be differenced into
     * individual records (a poller learns a number moved, not what it was), it
     * identifies nobody, and it is the same figure a city publishes willingly.
     * It is also the product's public face, which is the point.
     *
     * It lives under /wards rather than /dashboard because that prefix is
     * already entirely public. Carving a permitAll hole inside an officer-only
     * prefix would flip the default for everything added there later from
     * "officer-only" to "whatever the author remembered".
     */
    @GetMapping("/summary")
    public ResponseEntity<List<WardSummaryDto>> summary() {
        return ResponseEntity.ok()
                // Also blunts the "scrape a time series of civic dysfunction"
                // concern, which is a reputational risk for the city rather
                // than a privacy risk for any resident.
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(wardService.summary());
    }

    /**
     * Derives the ward from a coordinate pair, so the submit form can show the
     * resident that the system already knows where they are.
     *
     * 404 when nothing covers the point — the caller falls back to the manual
     * picker rather than us filing the report into a guessed ward.
     */
    @GetMapping("/resolve")
    public ResponseEntity<WardDto> resolve(
            @RequestParam("lat") double lat,
            @RequestParam("long") double lng) {

        if (lat < -90 || lat > 90) {
            throw new ValidationException("lat must be between -90 and 90");
        }
        if (lng < -180 || lng > 180) {
            throw new ValidationException("long must be between -180 and 180");
        }

        return ResponseEntity.ok(wardService.resolve(lat, lng));
    }

    // Declared after /summary and /resolve so those literal paths are not
    // shadowed by the {id} template.
    @GetMapping("/{id}")
    public ResponseEntity<WardDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(wardService.getById(id));
    }
}
