package com.bookworm.order;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "OrderStatusHistory")
public record OrderStatusHistoryResponse(
        OrderStatus fromStatus,
        OrderStatus toStatus,
        String changedBy,
        String reason,
        Instant changedAt) {

    public static OrderStatusHistoryResponse from(OrderStatusHistory history) {
        return new OrderStatusHistoryResponse(
                history.getFromStatus(),
                history.getToStatus(),
                history.getChangedBy(),
                history.getReason(),
                history.getChangedAt());
    }
}
