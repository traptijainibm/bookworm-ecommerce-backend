package com.bookworm.order;

import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "OrderStatusUpdate")
public record OrderStatusUpdateRequest(
        @NotNull(message = "status is required")
        OrderStatus status,
        
        String reason) {
}
