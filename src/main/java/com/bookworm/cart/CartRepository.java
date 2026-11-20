package com.bookworm.cart;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** All queries are parameterized. The user id passed in always comes from the validated token. */
public interface CartRepository extends JpaRepository<Cart, Long> {

    /** Plain read for {@code GET /me/cart}; takes no lock and creates nothing. */
    Optional<Cart> findByUserId(Long userId);

    /**
     * Race-free "ensure the cart exists": concurrent first requests for the same customer end with exactly one
     * row (the loser of {@code UNIQUE (user_id)} does nothing).
     */
    @Modifying
    @Query(value = "insert into carts (user_id) values (:userId) on conflict (user_id) do nothing",
            nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId);

    /** {@code SELECT ... FOR UPDATE} on the caller's cart row: serialises every mutation of that cart. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.userId = :userId")
    Optional<Cart> findByUserIdForUpdate(@Param("userId") Long userId);
}
