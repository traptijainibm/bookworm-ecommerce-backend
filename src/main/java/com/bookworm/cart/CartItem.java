package com.bookworm.cart;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One line of a cart: a book and a quantity (1-10, also a database CHECK). {@code cartId} and {@code bookId} are
 * plain columns, not JPA associations, so the cart package never maps into the catalogue. No price is stored.
 */
@Entity
@Table(name = "cart_items")
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long cartId;

    private Long bookId;

    private int quantity;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    protected CartItem() {
    }

    public static CartItem create(Long cartId, Long bookId, int quantity) {
        CartItem item = new CartItem();
        item.cartId = cartId;
        item.bookId = bookId;
        item.quantity = quantity;
        item.createdAt = Instant.now();
        item.updatedAt = item.createdAt;
        return item;
    }

    void changeQuantity(int quantity) {
        this.quantity = quantity;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getCartId() {
        return cartId;
    }

    public Long getBookId() {
        return bookId;
    }

    public int getQuantity() {
        return quantity;
    }
}
