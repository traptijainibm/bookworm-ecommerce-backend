package com.bookworm.loyalty;

import java.time.Instant;

public record PointsTransactionResponse(
        Long id,
        Long orderId,
        PointsTransactionType type,
        long points,
        Instant createdAt) {

    public static PointsTransactionResponse from(PointsTransaction tx) {
        return new PointsTransactionResponse(
                tx.getId(),
                tx.getOrderId(),
                tx.getType(),
                tx.getPoints(),
                tx.getCreatedAt());
    }
}
