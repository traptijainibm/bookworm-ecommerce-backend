package com.bookworm.cart;

import com.bookworm.catalogue.BookInfo;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * One cart line as the customer sees it. An available line carries the live catalogue data; a line whose book
 * was deactivated carries only {@code bookId}, {@code title} and {@code availability = UNAVAILABLE} (the other
 * fields are null and omitted from the JSON). The stock count is never included.
 */
@Schema(name = "CartItem")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CartItemResponse(
        Long bookId,
        String isbn,
        String title,
        List<Author> authors,
        String coverUrl,
        BigDecimal unitPrice,
        Integer quantity,
        BigDecimal lineTotal,
        ItemAvailability availability) {

    public record Author(Long id, String name) {
    }

    static CartItemResponse of(CartItem line, BookInfo book) {
        BigDecimal unitPrice = book.price().setScale(2, RoundingMode.HALF_UP);
        BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(line.getQuantity()))
                .setScale(2, RoundingMode.HALF_UP);
        List<Author> authors = book.authors().stream().map(a -> new Author(a.id(), a.name())).toList();
        return new CartItemResponse(book.id(), book.isbn(), book.title(), authors, book.coverUrl(), unitPrice,
                line.getQuantity(), lineTotal, availabilityOf(book.stockQuantity(), line.getQuantity()));
    }

    /** {@code title} is null only if the book row were missing, which the foreign key prevents. */
    static CartItemResponse unavailable(Long bookId, String title) {
        return new CartItemResponse(bookId, null, title, null, null, null, null, null,
                ItemAvailability.UNAVAILABLE);
    }

    /** For an active book: out of stock, short of stock for this quantity, or available. */
    static ItemAvailability availabilityOf(int stock, int quantity) {
        if (stock <= 0) {
            return ItemAvailability.OUT_OF_STOCK;
        }
        return stock < quantity ? ItemAvailability.INSUFFICIENT_STOCK : ItemAvailability.AVAILABLE;
    }
}
