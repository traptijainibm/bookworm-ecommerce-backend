package com.bookworm.catalogue;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code bookCount} counts all books by the author, active or not. */
@Schema(name = "AdminAuthor")
public record AdminAuthorResponse(Long id, String name, long bookCount) {

    static AdminAuthorResponse from(AdminAuthor author, long bookCount) {
        return new AdminAuthorResponse(author.getId(), author.getName(), bookCount);
    }
}
