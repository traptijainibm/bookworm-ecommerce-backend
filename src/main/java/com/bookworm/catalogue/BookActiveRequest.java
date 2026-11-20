package com.bookworm.catalogue;

import com.bookworm.common.web.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Activate or deactivate a book. {@code active} is required (a missing value is a 400, never "false"). */
@Schema(name = "BookActiveRequest")
public record BookActiveRequest(@NotNull Boolean active) implements StrictRequest {
}
