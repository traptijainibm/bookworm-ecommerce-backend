package com.bookworm.cart;

import com.bookworm.catalogue.BookInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The customer's cart. Money is {@code BigDecimal} at scale 2 (INR). {@code subtotal} and {@code itemCount}
 * cover active lines only; out-of-stock and short-of-stock lines still count toward {@code subtotal} because the
 * price is real. {@code checkoutReady} is advisory: true only for a non-empty cart whose every line is
 * {@code AVAILABLE}; checkout re-validates everything.
 */
@Schema(name = "Cart")
public record CartResponse(
        List<CartItemResponse> items,
        int lineCount,
        int itemCount,
        BigDecimal subtotal,
        boolean checkoutReady) {

    public static CartResponse empty() {
        return new CartResponse(List.of(), 0, 0, BigDecimal.ZERO.setScale(2), false);
    }

    /** Lines in the given (insertion) order; {@code books} is keyed by id and may include inactive books. */
    public static CartResponse from(List<CartItem> lines, Map<Long, BookInfo> books) {
        List<CartItemResponse> items = new ArrayList<>();
        int itemCount = 0;
        BigDecimal subtotal = BigDecimal.ZERO.setScale(2);
        boolean allAvailable = true;
        for (CartItem line : lines) {
            BookInfo book = books.get(line.getBookId());
            if (book == null || !book.active()) {
                items.add(CartItemResponse.unavailable(line.getBookId(), book == null ? null : book.title()));
                allAvailable = false;
                continue;
            }
            CartItemResponse item = CartItemResponse.of(line, book);
            items.add(item);
            itemCount += line.getQuantity();
            subtotal = subtotal.add(item.lineTotal());
            allAvailable &= item.availability() == ItemAvailability.AVAILABLE;
        }
        return new CartResponse(List.copyOf(items), lines.size(), itemCount, subtotal,
                !lines.isEmpty() && allAvailable);
    }
}
