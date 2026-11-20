package com.bookworm.wishlist;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link WishlistItem}. All queries are user-scoped.
 */
public interface WishlistItemRepository extends JpaRepository<WishlistItem, Long> {

    /** Paginated items for the customer. */
    Page<WishlistItem> findByUserId(Long userId, Pageable pageable);

    /** Looks up an item in the caller's own wishlist. */
    Optional<WishlistItem> findByUserIdAndBookId(Long userId, Long bookId);

    /** Count of items in the customer's wishlist (capped at 200). */
    long countByUserId(Long userId);

    /** Idempotent remove: deleting an absent item succeeds. */
    @Modifying
    @Query("delete from WishlistItem w where w.userId = :userId and w.bookId = :bookId")
    int deleteByUserIdAndBookId(@Param("userId") Long userId, @Param("bookId") Long bookId);

    /**
     * Acquires a transaction-scoped advisory lock for the user's wishlist operations.
     * Prevents race conditions when checking and enforcing the 200-item cap.
     */
    @Query(value = "select pg_advisory_xact_lock(:key)", nativeQuery = true)
    void acquireAdvisoryLock(@Param("key") long key);
}
