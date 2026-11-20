package com.bookworm.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Customer order entity.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNumber;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false)
    private long pointsRedeemed;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal pointsDiscount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(nullable = false)
    private long pointsEarned;

    @Embedded
    private ShippingAddressEmbeddable shippingAddress;

    private String idempotencyKey;

    private String requestFingerprint;

    @Version
    private Long version;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    protected Order() {
    }

    public static Order create(
            String orderNumber,
            Long userId,
            BigDecimal subtotal,
            long pointsRedeemed,
            BigDecimal pointsDiscount,
            BigDecimal total,
            long pointsEarned,
            ShippingAddressEmbeddable shippingAddress,
            String idempotencyKey,
            String requestFingerprint) {
        Order order = new Order();
        order.orderNumber = orderNumber;
        order.userId = userId;
        order.status = OrderStatus.PLACED;
        order.subtotal = subtotal;
        order.pointsRedeemed = pointsRedeemed;
        order.pointsDiscount = pointsDiscount;
        order.total = total;
        order.pointsEarned = pointsEarned;
        order.shippingAddress = shippingAddress;
        order.idempotencyKey = idempotencyKey;
        order.requestFingerprint = requestFingerprint;
        order.createdAt = Instant.now();
        order.updatedAt = order.createdAt;
        return order;
    }

    public void changeStatus(OrderStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public Long getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public long getPointsRedeemed() {
        return pointsRedeemed;
    }

    public BigDecimal getPointsDiscount() {
        return pointsDiscount;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public long getPointsEarned() {
        return pointsEarned;
    }

    public ShippingAddressEmbeddable getShippingAddress() {
        return shippingAddress;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
