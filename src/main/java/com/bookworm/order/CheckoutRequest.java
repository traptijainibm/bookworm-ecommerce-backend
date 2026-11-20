package com.bookworm.order;

import com.bookworm.common.web.StrictLongDeserializer;
import com.bookworm.common.web.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Checkout request body.
 */
@Schema(name = "CheckoutRequest")
public record CheckoutRequest(
        @NotNull @Valid ShippingAddressDto shippingAddress,
        @NotBlank @Schema(description = "Simulated payment token: tok_success, tok_declined, tok_error") String paymentToken,
        @Min(0) @JsonDeserialize(using = StrictLongDeserializer.class)
        @Schema(description = "Optional loyalty points to redeem") Long redeemPoints)
        implements StrictRequest {

    public long safeRedeemPoints() {
        return redeemPoints != null ? redeemPoints : 0L;
    }
}
