package com.bookworm.cart;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Lines are always read through the caller's own cart id, so another customer's line is unreachable. */
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    /** Lines in insertion order. */
    List<CartItem> findByCartIdOrderByIdAsc(Long cartId);
}
