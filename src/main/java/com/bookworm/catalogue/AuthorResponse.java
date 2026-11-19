package com.bookworm.catalogue;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Author")
public record AuthorResponse(Long id, String name) {

    static AuthorResponse from(Author author) {
        return new AuthorResponse(author.getId(), author.getName());
    }
}
