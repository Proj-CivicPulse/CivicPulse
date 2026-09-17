package com.civicpulse.backend_spring.controller;

import com.civicpulse.backend_spring.dto.category.CategoryDto;
import com.civicpulse.backend_spring.service.category.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Public reference data, alongside /wards. Readable without a session because
 * the submit form is itself anonymous — it has to render the category picker
 * before anyone has signed in.
 *
 * <p>Discloses nothing but the city's own vocabulary: no counts, no incidents,
 * nothing derived from a resident's report.
 */
@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * Active categories in submit-form order.
     *
     * <p>Cached for an hour. The vocabulary changes about as often as the ward
     * list does, and the submit form fetches this on every load — a resident
     * opening the page should not wait on a round trip for a list of six rows.
     * The cost of the staleness is that a newly added category takes up to an
     * hour to appear in a browser that has already loaded the page, which is
     * the right trade for reference data of this kind.
     */
    @GetMapping
    public ResponseEntity<List<CategoryDto>> list() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(categoryService.listActive());
    }
}
