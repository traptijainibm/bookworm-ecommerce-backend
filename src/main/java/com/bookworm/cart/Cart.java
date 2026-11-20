package com.bookworm.cart;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * One cart per customer ({@code UNIQUE (user_id)}). The row is created only by
 * {@link CartRepository#insertIfAbsent(Long)} (race-free) and is locked with
 * {@link CartRepository#findByUserIdForUpdate(Long)} for every cart mutation; this entity is never inserted
 * through the persistence context. It holds no price and no stock: the cart is only a list of lines.
 */
@Entity
@Table(name = "carts")
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Version
    private Long version;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    protected Cart() {
    }

    /** Marks the cart as changed (also bumps {@code version}); called by every mutation that changes a line. */
    public void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
