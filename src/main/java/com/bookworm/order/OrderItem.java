package com.bookworm.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable line item of an order, capturing the price and metadata snapshot at the time of purchase.
 */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long bookId;

    @Column(nullable = false)
    private String isbnSnapshot;

    @Column(nullable = false)
    private String titleSnapshot;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    @Column(updatable = false)
    private Instant createdAt;

    protected OrderItem() {
    }

    public static OrderItem create(
            Long orderId,
            Long bookId,
            String isbnSnapshot,
            String titleSnapshot,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineTotal) {
        OrderItem item = new OrderItem();
        item.orderId = orderId;
        item.bookId = bookId;
        item.isbnSnapshot = isbnSnapshot;
        item.titleSnapshot = titleSnapshot;
        item.unitPrice = unitPrice;
        item.quantity = quantity;
        item.lineTotal = lineTotal;
        item.createdAt = Instant.now();
        return item;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getBookId() {
        return bookId;
    }

    public String getIsbnSnapshot() {
        return isbnSnapshot;
    }

    public String getTitleSnapshot() {
        return titleSnapshot;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
