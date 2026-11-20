package com.bookworm.wishlist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One item in a customer's wishlist. {@code userId} and {@code bookId} are plain columns, not JPA associations,
 * so the wishlist package never maps into the catalogue or user entities.
 */
@Entity
@Table(name = "wishlist_items")
public class WishlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private Long bookId;

    @Column(updatable = false)
    private Instant addedAt;

    protected WishlistItem() {
    }

    public static WishlistItem create(Long userId, Long bookId) {
        WishlistItem item = new WishlistItem();
        item.userId = userId;
        item.bookId = bookId;
        item.addedAt = Instant.now();
        return item;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getBookId() {
        return bookId;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
