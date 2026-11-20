package com.bookworm.cart;

/**
 * How a cart line can be fulfilled right now. {@code UNAVAILABLE} means the book was deactivated after it was
 * added. The stock count itself is never exposed.
 */
public enum ItemAvailability {
    AVAILABLE,
    OUT_OF_STOCK,
    INSUFFICIENT_STOCK,
    UNAVAILABLE
}
