package com.bookworm.catalogue;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code bookCount} counts all books in the category, active or not. */
@Schema(name = "AdminCategory")
public record AdminCategoryResponse(Long id, String name, String slug, long bookCount) {

    static AdminCategoryResponse from(AdminCategory category, long bookCount) {
        return new AdminCategoryResponse(category.getId(), category.getName(), category.getSlug(), bookCount);
    }
}
