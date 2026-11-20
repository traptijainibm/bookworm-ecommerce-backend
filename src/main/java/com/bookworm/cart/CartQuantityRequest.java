package com.bookworm.cart;

import com.bookworm.common.web.StrictIntegerDeserializer;
import com.bookworm.common.web.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Set the absolute quantity of an existing cart line. To remove a line use DELETE; 0 is rejected (400). */
@Schema(name = "CartQuantityRequest")
public record CartQuantityRequest(
        @NotNull @Min(1) @Max(10) @JsonDeserialize(using = StrictIntegerDeserializer.class)
        @Schema(description = "New quantity (1-10)") Integer quantity) implements StrictRequest {
}
