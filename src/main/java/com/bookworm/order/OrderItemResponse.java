package com.bookworm.order;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(name = "OrderItem")
public record OrderItemResponse(
        Long bookId,
        String isbn,
        String title,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal) {

    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getBookId(),
                item.getIsbnSnapshot(),
                item.getTitleSnapshot(),
                item.getUnitPrice(),
                item.getQuantity(),
                item.getLineTotal());
    }
}
