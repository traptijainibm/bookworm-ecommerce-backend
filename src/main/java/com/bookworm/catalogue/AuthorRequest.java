package com.bookworm.catalogue;

import com.bookworm.common.web.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create or rename an author. */
@Schema(name = "AuthorRequest")
public record AuthorRequest(@NotBlank @Size(max = 800) String name) implements StrictRequest {
}
