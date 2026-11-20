package com.bookworm.wishlist;

import com.bookworm.cart.Cart;
import com.bookworm.cart.CartItem;
import com.bookworm.cart.CartItemRepository;
import com.bookworm.cart.CartRepository;
import com.bookworm.cart.CartResponse;
import com.bookworm.catalogue.BookInfo;
import com.bookworm.catalogue.BookLookupService;
import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Move use cases between cart and wishlist (Phase 4).
 * <ul>
 *   <li>Lock hierarchy (strictly enforced):
 *       1. Cart row lock first ({@code SELECT ... FOR UPDATE} on the caller's cart, created first if absent).
 *       2. Wishlist advisory lock second ({@code pg_advisory_xact_lock}).
 *       Both move directions take locks in this exact order to prevent deadlocks.</li>
 *   <li>Atomic: source is modified only after target operation succeeds; failure rolls back both sides.</li>
 *   <li>Ownership first: source is looked up in caller's own cart/wishlist first (404 when absent), before book
 *       state is checked.</li>
 *   <li>Inactive source book: 409 BOOK_UNAVAILABLE.</li>
 *   <li>Response: 200 with updated CartResponse for both move directions.</li>
 * </ul>
 */
@Service
public class WishlistMoveService {

    private static final int MAX_CART_LINES = 50;
    private static final int MAX_WISHLIST_ITEMS = 200;

    private static final String CART_LINE_NOT_FOUND = "Cart item not found.";
    private static final String WISHLIST_ITEM_NOT_FOUND = "Wishlist item not found.";
    private static final String BOOK_UNAVAILABLE_MSG = "This book is no longer available.";

    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final WishlistItemRepository wishlistItems;
    private final BookLookupService books;

    public WishlistMoveService(CartRepository carts, CartItemRepository cartItems,
                               WishlistItemRepository wishlistItems, BookLookupService books) {
        this.carts = carts;
        this.cartItems = cartItems;
        this.wishlistItems = wishlistItems;
        this.books = books;
    }

    /**
     * Moves a book from the wishlist into the cart.
     * Takes cart lock first, then wishlist advisory lock.
     * Wishlist entry must exist in caller's wishlist (else 404).
     * Book must be active (else 409 BOOK_UNAVAILABLE).
     * If book already in cart, cart quantity is left unchanged.
     * If book not in cart: check CART_FULL (50 lines), check stock >= 1 (else 409 INSUFFICIENT_STOCK), add line with qty 1.
     * Wishlist entry is removed only after cart step succeeds.
     * Returns 200 with updated CartResponse.
     */
    @Transactional
    public CartResponse moveToCart(Long userId, Long bookId) {
        Cart cart = lockOrCreateCart(userId);
        wishlistItems.acquireAdvisoryLock(WishlistService.lockKey(userId));

        WishlistItem wishlistItem = wishlistItems.findByUserIdAndBookId(userId, bookId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, WISHLIST_ITEM_NOT_FOUND));

        BookInfo book = books.findById(bookId).filter(BookInfo::active)
                .orElseThrow(() -> new ApiException(ErrorCode.BOOK_UNAVAILABLE, BOOK_UNAVAILABLE_MSG));

        List<CartItem> lines = new ArrayList<>(cartItems.findByCartIdOrderByIdAsc(cart.getId()));
        CartItem existingLine = lineFor(lines, bookId);

        if (existingLine == null) {
            if (lines.size() >= MAX_CART_LINES) {
                throw new ApiException(ErrorCode.CART_FULL, "The cart cannot hold more than 50 different books.");
            }
            if (book.stockQuantity() < 1) {
                throw new ApiException(ErrorCode.INSUFFICIENT_STOCK,
                        "Not enough stock is available for the requested quantity.");
            }
            CartItem newLine = cartItems.save(CartItem.create(cart.getId(), bookId, 1));
            lines.add(newLine);
            cart.touch();
        }

        wishlistItems.delete(wishlistItem);
        return renderCart(lines);
    }

    /**
     * Moves a book from the cart into the wishlist.
     * Takes cart lock first, then wishlist advisory lock.
     * Cart line must exist in caller's cart (else 404).
     * Book must be active (else 409 BOOK_UNAVAILABLE).
     * If book already in wishlist, wishlist add is a no-op (no WISHLIST_FULL check).
     * If book not in wishlist: check WISHLIST_FULL (200 items), save wishlist item.
     * Cart line is removed (quantity discarded).
     * Returns 200 with updated CartResponse.
     */
    @Transactional
    public CartResponse moveToWishlist(Long userId, Long bookId) {
        Cart cart = carts.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, CART_LINE_NOT_FOUND));
        wishlistItems.acquireAdvisoryLock(WishlistService.lockKey(userId));

        List<CartItem> lines = new ArrayList<>(cartItems.findByCartIdOrderByIdAsc(cart.getId()));
        CartItem line = lineFor(lines, bookId);
        if (line == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, CART_LINE_NOT_FOUND);
        }

        BookInfo book = books.findById(bookId).filter(BookInfo::active)
                .orElseThrow(() -> new ApiException(ErrorCode.BOOK_UNAVAILABLE, BOOK_UNAVAILABLE_MSG));

        Optional<WishlistItem> existingWishlist = wishlistItems.findByUserIdAndBookId(userId, bookId);
        if (existingWishlist.isEmpty()) {
            if (wishlistItems.countByUserId(userId) >= MAX_WISHLIST_ITEMS) {
                throw new ApiException(ErrorCode.WISHLIST_FULL, "The wishlist cannot hold more than 200 items.");
            }
            wishlistItems.save(WishlistItem.create(userId, bookId));
        }

        cartItems.delete(line);
        lines.remove(line);
        cart.touch();

        return renderCart(lines);
    }

    private Cart lockOrCreateCart(Long userId) {
        Optional<Cart> locked = carts.findByUserIdForUpdate(userId);
        if (locked.isPresent()) {
            return locked.get();
        }
        carts.insertIfAbsent(userId);
        return carts.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("Cart row missing after insert"));
    }

    private static CartItem lineFor(List<CartItem> lines, Long bookId) {
        for (CartItem line : lines) {
            if (line.getBookId().equals(bookId)) {
                return line;
            }
        }
        return null;
    }

    private CartResponse renderCart(List<CartItem> lines) {
        List<Long> bookIds = lines.stream().map(CartItem::getBookId).toList();
        return CartResponse.from(lines, books.findByIds(bookIds));
    }
}
