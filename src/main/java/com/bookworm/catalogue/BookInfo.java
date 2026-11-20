package com.bookworm.catalogue;

import java.math.BigDecimal;
import java.util.List;

/**
 * Internal, read-only view of a book for other features (cart, wishlist), returned by {@link BookLookupService}.
 * It is never a response body: {@code stockQuantity} and {@code active} exist only so callers can compute
 * availability and check stock, and must not be serialised to clients. It carries no entity.
 */
public record BookInfo(
        Long id,
        String isbn,
        String title,
        List<AuthorRef> authors,
        CategoryRef category,
        BigDecimal price,
        String coverUrl,
        boolean active,
        int stockQuantity) {

    public record AuthorRef(Long id, String name) {
    }

    public record CategoryRef(Long id, String name, String slug) {
    }

    static BookInfo from(Book book) {
        List<AuthorRef> authors = book.getAuthors().stream()
                .map(a -> new AuthorRef(a.getId(), a.getName()))
                .toList();
        Category category = book.getCategory();
        return new BookInfo(book.getId(), book.getIsbn(), book.getTitle(), authors,
                new CategoryRef(category.getId(), category.getName(), category.getSlug()),
                book.getPrice(), book.getCoverUrl(), book.isActive(), book.getStockQuantity());
    }
}
