package com.bookworm.cart;

import com.bookworm.common.web.StrictIntegerDeserializer;
import com.bookworm.common.web.StrictLongDeserializer;
import com.bookworm.common.web.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Add a book to the cart. Both numbers must be real JSON integers (strict deserializers, no string or decimal
 * coercion); unknown fields such as {@code price} or {@code userId} are rejected (400). The customer is never
 * taken from the body.
 */
@Schema(name = "CartAddRequest")
public record CartAddRequest(
        @NotNull @Min(1) @JsonDeserialize(using = StrictLongDeserializer.class) Long bookId,
        @NotNull @Min(1) @Max(10) @JsonDeserialize(using = StrictIntegerDeserializer.class)
        @Schema(description = "Copies to add (1-10); an existing line is increased by this amount") Integer quantity)
        implements StrictRequest {
}
