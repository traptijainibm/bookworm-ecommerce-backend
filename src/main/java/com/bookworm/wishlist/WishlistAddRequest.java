package com.bookworm.wishlist;

import com.bookworm.common.web.StrictLongDeserializer;
import com.bookworm.common.web.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Add a book to the customer's wishlist. The number must be a real JSON integer (strict deserializer, no string
 * or decimal coercion); unknown fields such as {@code userId} or {@code price} are rejected (400).
 */
@Schema(name = "WishlistAddRequest")
public record WishlistAddRequest(
        @NotNull @Min(1) @JsonDeserialize(using = StrictLongDeserializer.class)
        @Schema(description = "ID of the book to add to the wishlist") Long bookId)
        implements StrictRequest {
}
