package com.bookworm.catalogue;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** A book in an admin list: the same as {@link AdminBookResponse} without the description. */
@Schema(name = "AdminBookSummary")
public record AdminBookSummaryResponse(
        Long id,
        String isbn,
        String title,
        List<AdminBookResponse.Author> authors,
        AdminBookResponse.Category category,
        BigDecimal price,
        String publisher,
        LocalDate publishedDate,
        String coverUrl,
        boolean active,
        int stockQuantity,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    static AdminBookSummaryResponse from(AdminBook book) {
        return new AdminBookSummaryResponse(
                book.getId(),
                book.getIsbn(),
                book.getTitle(),
                AdminBookResponse.sortedAuthors(book),
                new AdminBookResponse.Category(book.getCategory().getId(), book.getCategory().getName(),
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
