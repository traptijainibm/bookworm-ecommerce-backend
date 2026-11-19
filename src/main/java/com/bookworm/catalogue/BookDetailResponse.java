package com.bookworm.catalogue;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Full book details. Exposes availability as {@code inStock} only, never the stock count. */
@Schema(name = "BookDetail")
public record BookDetailResponse(
        Long id,
        String isbn,
        String title,
        String description,
        List<AuthorResponse> authors,
        CategoryResponse category,
        BigDecimal price,
        String publisher,
        LocalDate publishedDate,
        String coverUrl,
        boolean inStock) {

    static BookDetailResponse from(Book book) {
        return new BookDetailResponse(
                book.getId(),
                book.getIsbn(),
                book.getTitle(),
                book.getDescription(),
                book.getAuthors().stream().map(AuthorResponse::from).toList(),
                CategoryResponse.from(book.getCategory()),
                book.getPrice(),
                book.getPublisher(),
                book.getPublishedDate(),
                book.getCoverUrl(),
                book.getStockQuantity() > 0);
    }
}
