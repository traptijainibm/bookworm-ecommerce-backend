package com.bookworm.catalogue;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Category")
public record CategoryResponse(Long id, String name, String slug) {

    static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getSlug());
    }
}
