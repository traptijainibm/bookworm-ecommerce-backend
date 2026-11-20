package com.bookworm.catalogue;

import com.bookworm.common.web.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create or rename a category. The slug is generated from the name on create and never changes. */
@Schema(name = "CategoryRequest")
public record CategoryRequest(@NotBlank @Size(max = 400) String name) implements StrictRequest {
}
