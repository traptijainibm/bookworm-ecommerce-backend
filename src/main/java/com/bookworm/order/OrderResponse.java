package com.bookworm.order;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(name = "Order")
public record OrderResponse(
        String orderNumber,
        OrderStatus status,
        BigDecimal subtotal,
        long pointsRedeemed,
        BigDecimal pointsDiscount,
        BigDecimal total,
        long pointsEarned,
        ShippingAddressDto shippingAddress,
        List<OrderItemResponse> items,
        List<OrderStatusHistoryResponse> statusHistory,
        Instant createdAt) {

    public static OrderResponse of(Order order, List<OrderItem> items) {
        return of(order, items, List.of());
    }

    public static OrderResponse of(Order order, List<OrderItem> items, List<OrderStatusHistory> history) {
        List<OrderItemResponse> itemResponses = items.stream()
                .map(OrderItemResponse::from)
                .toList();
        List<OrderStatusHistoryResponse> historyResponses = history.stream()
                .map(OrderStatusHistoryResponse::from)
                .toList();
        return new OrderResponse(
                order.getOrderNumber(),
                order.getStatus(),
                order.getSubtotal(),
                order.getPointsRedeemed(),
                order.getPointsDiscount(),
                order.getTotal(),
                order.getPointsEarned(),
                ShippingAddressDto.from(order.getShippingAddress()),
                itemResponses,
                historyResponses,
                order.getCreatedAt());
    }
}
