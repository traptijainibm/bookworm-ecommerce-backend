package com.bookworm.catalogue;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Full book as seen by an admin. {@code stockQuantity} is read-only here; there is no endpoint to change it. */
@Schema(name = "AdminBook")
public record AdminBookResponse(
        Long id,
        String isbn,
        String title,
        String description,
        List<Author> authors,
        Category category,
        BigDecimal price,
        String publisher,
        LocalDate publishedDate,
        String coverUrl,
        boolean active,
        int stockQuantity,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    public record Author(Long id, String name) {
    }

    public record Category(Long id, String name, String slug) {
    }

    /**
     * Authors by name then id. Sorted here (not only by the {@code @OrderBy} on the mapping) so a book just
     * created in this request lists its authors in the same order as when it is read back later.
     */
    static List<Author> sortedAuthors(AdminBook book) {
        return book.getAuthors().stream()
                .sorted(java.util.Comparator.comparing(AdminAuthor::getName).thenComparing(AdminAuthor::getId))
                .map(a -> new Author(a.getId(), a.getName()))
                .toList();
    }

    static AdminBookResponse from(AdminBook book) {
        return new AdminBookResponse(
                book.getId(),
                book.getIsbn(),
                book.getTitle(),
                book.getDescription(),
                sortedAuthors(book),
                new Category(book.getCategory().getId(), book.getCategory().getName(),
                        book.getCategory().getSlug()),
                book.getPrice(),
                book.getPublisher(),
                book.getPublishedDate(),
                book.getCoverUrl(),
                book.isActive(),
                book.getStockQuantity(),
                book.getVersion(),
                book.getCreatedAt(),
                book.getUpdatedAt());
    }
}
