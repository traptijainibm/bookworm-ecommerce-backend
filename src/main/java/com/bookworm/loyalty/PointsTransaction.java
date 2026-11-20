package com.bookworm.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "points_transactions")
public class PointsTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PointsTransactionType type;

    @Column(nullable = false)
    private long points;

    @Column(updatable = false)
    private Instant createdAt;

    protected PointsTransaction() {
    }

    public static PointsTransaction create(Long userId, Long orderId, PointsTransactionType type, long points) {
        PointsTransaction tx = new PointsTransaction();
        tx.userId = userId;
        tx.orderId = orderId;
        tx.type = type;
        tx.points = points;
        tx.createdAt = Instant.now();
        return tx;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public PointsTransactionType getType() {
        return type;
    }

    public long getPoints() {
        return points;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
