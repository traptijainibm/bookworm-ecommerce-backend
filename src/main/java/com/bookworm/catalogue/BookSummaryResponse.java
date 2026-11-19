package com.bookworm.catalogue;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/** List/search item. Exposes availability as {@code inStock} only, never the stock count. */
@Schema(name = "BookSummary")
public record BookSummaryResponse(
        Long id,
        String isbn,
        String title,
        List<AuthorResponse> authors,
        CategoryResponse category,
        BigDecimal price,
        String coverUrl,
        boolean inStock) {

    static BookSummaryResponse from(Book book) {
        return new BookSummaryResponse(
                book.getId(),
                book.getIsbn(),
                book.getTitle(),
                book.getAuthors().stream().map(AuthorResponse::from).toList(),
                CategoryResponse.from(book.getCategory()),
                book.getPrice(),
                book.getCoverUrl(),
                book.getStockQuantity() > 0);
    }
}
