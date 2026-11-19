package com.bookworm.catalogue;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import com.bookworm.common.persistence.LikePatterns;
import com.bookworm.common.web.PageResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only catalogue use cases (Phase 1). Nothing here writes to the database. */
@Service
@Transactional(readOnly = true)
public class CatalogueService {

    /** Public sort keys mapped to entity properties. Anything else is rejected before reaching here. */
    static final Map<String, String> SORT_FIELDS = Map.of(
            "title", "title",
            "price", "price",
            "publishedDate", "publishedDate");

    private final BookRepository bookRepository;
    private final CategoryRepository categoryRepository;

    public CatalogueService(BookRepository bookRepository, CategoryRepository categoryRepository) {
        this.bookRepository = bookRepository;
        this.categoryRepository = categoryRepository;
    }

    public PageResponse<BookSummaryResponse> search(String query, String categorySlug, int page, int size,
                                                    String sortField, Sort.Direction direction) {
        String property = SORT_FIELDS.get(sortField);
        if (property == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported sort field.");
        }
        // id is the tie-breaker so paging is stable when the sort key has duplicates.
        Sort sort = Sort.by(direction, property).and(Sort.by(Sort.Direction.ASC, "id"));
        Pageable pageable = PageRequest.of(page, size, sort);

        String trimmed = query == null ? "" : query.trim();
        String pattern = trimmed.isEmpty() ? "" : LikePatterns.containsIgnoreCase(trimmed);
        String slug = categorySlug == null ? "" : categorySlug;

        return PageResponse.of(bookRepository.search(slug, pattern, pageable).map(BookSummaryResponse::from));
    }

    public BookDetailResponse getBook(Long id) {
        return BookDetailResponse.from(findActive(id));
    }

    /** Books sharing an author first, then books in the same category; the book itself is never included. */
    public List<BookSummaryResponse> getRelated(Long id, int limit) {
        Book book = findActive(id);
        Pageable limitPage = PageRequest.of(0, limit);

        List<Book> related = new ArrayList<>(bookRepository.findSharingAuthor(id, limitPage));
        if (related.size() < limit) {
            Set<Long> excluded = new LinkedHashSet<>();
            excluded.add(id);
            related.forEach(b -> excluded.add(b.getId()));
            related.addAll(bookRepository.findInCategoryExcluding(
                    book.getCategory().getId(), excluded, PageRequest.of(0, limit - related.size())));
        }
        return related.stream().map(BookSummaryResponse::from).toList();
    }

    public List<CategoryResponse> getCategories() {
        return categoryRepository.findAllByOrderByNameAscIdAsc().stream().map(CategoryResponse::from).toList();
    }

    private Book findActive(Long id) {
        return bookRepository.findActiveById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Book not found."));
    }
}
