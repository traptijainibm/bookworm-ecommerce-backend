package com.bookworm.cart;

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
 * Shopping cart use cases (Phase 4). The customer id always comes from the validated token.
 * <ul>
 *   <li>Every mutation runs in one transaction that takes the customer's cart row lock first
 *       ({@code SELECT ... FOR UPDATE}), so parallel requests for one cart serialise and cannot duplicate a line
 *       or exceed a limit. Only {@code add} creates the cart row (race-free, {@code ON CONFLICT DO NOTHING}).</li>
 *   <li>Prices are always live from the catalogue; nothing price-related is stored. Stock is only read (to refuse
 *       a quantity above it): there is no reservation and no write to {@code books}.</li>
 *   <li>Books are read through {@link BookLookupService} only, never through a catalogue entity or an admin
 *       class.</li>
 *   <li>No message here contains a stock count.</li>
 * </ul>
 */
@Service
public class CartService {

    static final int MAX_LINES = 50;
    static final int MAX_QUANTITY = 10;

    private static final String BOOK_NOT_FOUND = "Book not found.";
    private static final String LINE_NOT_FOUND = "Cart item not found.";

    private final CartRepository carts;
    private final CartItemRepository items;
    private final BookLookupService books;

    public CartService(CartRepository carts, CartItemRepository items, BookLookupService books) {
        this.carts = carts;
        this.items = items;
        this.books = books;
    }

    /** Read-only: creates nothing, so a customer who never added anything sees an empty cart. */
    @Transactional(readOnly = true)
    public CartResponse get(Long userId) {
        return carts.findByUserId(userId)
                .map(cart -> render(items.findByCartIdOrderByIdAsc(cart.getId())))
                .orElseGet(CartResponse::empty);
    }

    /**
     * Adds {@code quantity} copies; an existing line is increased (one line per book). Under the cart lock the
     * checks run in this order: book unknown or inactive (404, also when the caller already holds a line for it),
     * line limit, quantity limit, stock. A rejected request changes nothing (the transaction rolls back,
     * including a just-created cart row).
     */
    @Transactional
    public CartResponse add(Long userId, Long bookId, int quantity) {
        Cart cart = lockOrCreate(userId);
        BookInfo book = books.findById(bookId).filter(BookInfo::active)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, BOOK_NOT_FOUND));
        List<CartItem> lines = new ArrayList<>(items.findByCartIdOrderByIdAsc(cart.getId()));
        CartItem existing = lineFor(lines, bookId);

        int merged = quantity;
        if (existing == null) {
            if (lines.size() >= MAX_LINES) {
                throw new ApiException(ErrorCode.CART_FULL, "The cart cannot hold more than 50 different books.");
            }
        } else {
            merged += existing.getQuantity();
        }
        if (merged > MAX_QUANTITY) {
            throw new ApiException(ErrorCode.QUANTITY_LIMIT_EXCEEDED,
                    "A cart line cannot hold more than 10 copies.");
        }
        requireStock(book, merged);

        if (existing == null) {
            lines.add(items.save(CartItem.create(cart.getId(), bookId, merged)));
        } else {
            existing.changeQuantity(merged);
        }
        cart.touch();
        return render(lines);
    }

    /**
     * Sets the absolute quantity of an existing line (idempotent). The line is looked up in the caller's own cart
     * first (404 when absent); only then is the book examined: inactive is 409 BOOK_UNAVAILABLE, above stock is
     * 409 INSUFFICIENT_STOCK.
     */
    @Transactional
    public CartResponse setQuantity(Long userId, Long bookId, int quantity) {
        Cart cart = carts.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, LINE_NOT_FOUND));
        List<CartItem> lines = new ArrayList<>(items.findByCartIdOrderByIdAsc(cart.getId()));
        CartItem line = lineFor(lines, bookId);
        if (line == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, LINE_NOT_FOUND);
        }
        BookInfo book = books.findById(bookId).filter(BookInfo::active)
                .orElseThrow(() -> new ApiException(ErrorCode.BOOK_UNAVAILABLE,
                        "This book is no longer available."));
        requireStock(book, quantity);

        line.changeQuantity(quantity);
        cart.touch();
        return render(lines);
    }

    /** Idempotent: removing an absent line (or from a cart that does not exist) succeeds. Never examines the book. */
    @Transactional
    public CartResponse remove(Long userId, Long bookId) {
        Optional<Cart> locked = carts.findByUserIdForUpdate(userId);
        if (locked.isEmpty()) {
            return CartResponse.empty();
        }
        Cart cart = locked.get();
        List<CartItem> lines = new ArrayList<>(items.findByCartIdOrderByIdAsc(cart.getId()));
        CartItem line = lineFor(lines, bookId);
        if (line != null) {
            items.delete(line);
            lines.remove(line);
            cart.touch();
        }
        return render(lines);
    }

    /** Idempotent: removes every line and keeps the cart row (checkout locks it later). */
    @Transactional
    public CartResponse clear(Long userId) {
        Optional<Cart> locked = carts.findByUserIdForUpdate(userId);
        if (locked.isEmpty()) {
            return CartResponse.empty();
        }
        Cart cart = locked.get();
        List<CartItem> lines = new ArrayList<>(items.findByCartIdOrderByIdAsc(cart.getId()));
        if (!lines.isEmpty()) {
            items.deleteAll(lines);
            cart.touch();
        }
        return CartResponse.empty();
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** Locks the caller's cart row, creating the row first (race-free) if the customer has none yet. */
    private Cart lockOrCreate(Long userId) {
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

    /** Refuses a quantity above current stock (stock 0 included). The count is never put in the message. */
    private static void requireStock(BookInfo book, int quantity) {
        if (quantity > book.stockQuantity()) {
            throw new ApiException(ErrorCode.INSUFFICIENT_STOCK,
                    "Not enough stock is available for the requested quantity.");
        }
    }

    private CartResponse render(List<CartItem> lines) {
        List<Long> bookIds = lines.stream().map(CartItem::getBookId).toList();
        return CartResponse.from(lines, books.findByIds(bookIds));
    }
}
