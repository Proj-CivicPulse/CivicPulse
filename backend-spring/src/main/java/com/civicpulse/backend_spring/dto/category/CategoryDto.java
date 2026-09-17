package com.civicpulse.backend_spring.dto.category;

import com.civicpulse.backend_spring.entity.Category;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Public category reference data. Serves GET /categories, which is what the
 * submit form reads instead of carrying its own copy of the vocabulary.
 *
 * <p>{@code code} is the identifier a client submits; {@code displayName} is
 * the only thing it should ever render. Keeping those separate is what lets a
 * label be reworded without invalidating every stored complaint.
 *
 * <p>Aliases are not exposed. They are an ingestion concern, and publishing
 * them would invite clients to submit one directly and quietly depend on a
 * mapping that exists to be revised.
 */
@Getter
@AllArgsConstructor
public class CategoryDto {

    private final String code;
    private final String name;
    private final String displayName;

    public static CategoryDto from(Category category) {
        return new CategoryDto(
                category.getCode(),
                category.getName(),
                category.getDisplayName());
    }
}
