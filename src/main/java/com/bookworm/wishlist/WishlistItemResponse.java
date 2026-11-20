package com.bookworm.wishlist;

import com.bookworm.cart.ItemAvailability;
import com.bookworm.catalogue.BookInfo;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * One wishlist item as the customer sees it. An active book carries live catalogue data; an inactive book
 * carries only {@code bookId}, {@code title} and {@code availability = UNAVAILABLE} (all other fields null
 * and omitted from JSON). The stock count is never exposed.
 */
@Schema(name = "WishlistItem")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WishlistItemResponse(
        Long bookId,
        String isbn,
        String title,
        List<Author> authors,
        Category category,
        BigDecimal price,
        String coverUrl,
        ItemAvailability availability,
        Instant addedAt) {

    public record Author(Long id, String name) {
    }

    public record Category(Long id, String name, String slug) {
    }

    public static WishlistItemResponse of(WishlistItem item, BookInfo book) {
        BigDecimal unitPrice = book.price() == null ? null : book.price().setScale(2, RoundingMode.HALF_UP);
        List<Author> authors = book.authors() == null ? List.of()
                : book.authors().stream().map(a -> new Author(a.id(), a.name())).toList();
        Category category = book.category() == null ? null
                : new Category(book.category().id(), book.category().name(), book.category().slug());
        ItemAvailability availability = book.stockQuantity() > 0 ? ItemAvailability.AVAILABLE : ItemAvailability.OUT_OF_STOCK;
        return new WishlistItemResponse(
                book.id(),
                book.isbn(),
                book.title(),
                authors,
                category,
                unitPrice,
                book.coverUrl(),
                availability,
                item.getAddedAt());
    }

    public static WishlistItemResponse unavailable(Long bookId, String title) {
        return new WishlistItemResponse(bookId, null, title, null, null, null, null,
                ItemAvailability.UNAVAILABLE, null);
    }
}
