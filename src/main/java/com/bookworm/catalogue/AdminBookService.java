package com.bookworm.catalogue;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ApiException.FieldProblem;
import com.bookworm.common.error.ErrorCode;
import com.bookworm.common.persistence.LikePatterns;
import com.bookworm.common.web.PageResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin book use cases. Nothing here reads or writes stock: {@link AdminBook#getStockQuantity()} is a read-only
 * mapping, new books start inactive, and the active flag changes only through {@link #setActive}.
 */
@Service
public class AdminBookService {

    private static final Logger log = LoggerFactory.getLogger(AdminBookService.class);

    /** Public sort keys mapped to entity properties. Anything else is rejected. */
    static final Map<String, String> SORT_FIELDS = Map.of(
            "id", "id",
            "title", "title",
            "isbn", "isbn",
            "price", "price",
            "publishedDate", "publishedDate",
            "updatedAt", "updatedAt");

    private final AdminBookRepository books;
    private final AdminCategoryRepository categories;
    private final AdminAuthorRepository authors;

    public AdminBookService(AdminBookRepository books, AdminCategoryRepository categories,
                            AdminAuthorRepository authors) {
        this.books = books;
        this.categories = categories;
        this.authors = authors;
    }

    @Transactional
    public AdminBookResponse create(Long adminId, BookCreateRequest request) {
        List<FieldProblem> problems = new ArrayList<>();
        BookValues values = BookInputs.validate(request, problems);
        AdminCategory category = findCategory(request.categoryId(), problems);
        Set<AdminAuthor> bookAuthors = findAuthors(request.authorIds(), problems);
        failIfInvalid(problems);

        if (books.existsByIsbn(values.isbn())) {
            throw duplicateIsbn();
        }
        AdminBook book = AdminBook.create(values, category, bookAuthors);
        saveAndFlushMappingDuplicates(book);
        log.info("admin_audit action=book.create bookId={} adminId={}", book.getId(), adminId);
        return AdminBookResponse.from(book);
    }

    @Transactional
    public AdminBookResponse update(Long adminId, Long id, BookUpdateRequest request) {
        AdminBook book = books.findDetailById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Book not found."));
        if (!book.getVersion().equals(request.version())) {
            throw staleVersion();
        }
        List<FieldProblem> problems = new ArrayList<>();
        BookValues values = BookInputs.validate(request, problems);
        AdminCategory category = findCategory(request.categoryId(), problems);
        Set<AdminAuthor> bookAuthors = findAuthors(request.authorIds(), problems);
        failIfInvalid(problems);

        if (books.existsByIsbnAndIdNot(values.isbn(), id)) {
            throw duplicateIsbn();
        }
        book.apply(values, category, bookAuthors);
        saveAndFlushMappingDuplicates(book);
        log.info("admin_audit action=book.update bookId={} adminId={}", id, adminId);
        return AdminBookResponse.from(book);
    }

    /** Idempotent: setting the current value changes nothing (and does not bump the version). */
    @Transactional
    public AdminBookResponse setActive(Long adminId, Long id, BookActiveRequest request) {
        AdminBook book = books.findDetailById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Book not found."));
        boolean desired = Boolean.TRUE.equals(request.active());
        if (book.isActive() != desired) {
            book.setActive(desired);
            books.flush();
            log.info("admin_audit action=book.{} bookId={} adminId={}",
                    desired ? "activate" : "deactivate", id, adminId);
        }
        return AdminBookResponse.from(book);
    }

    @Transactional(readOnly = true)
    public AdminBookResponse get(Long id) {
        return AdminBookResponse.from(books.findDetailById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Book not found.")));
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminBookSummaryResponse> search(String query, Long categoryId, Boolean active, int page,
                                                         int size, String sortField, Sort.Direction direction) {
        String property = SORT_FIELDS.get(sortField);
        if (property == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported sort field.");
        }
        Sort sort = Sort.by(direction, property);
        if (!"id".equals(property)) {
            sort = sort.and(Sort.by(Sort.Direction.ASC, "id"));
        }
        String trimmed = query == null ? "" : query.trim();
        String pattern = trimmed.isEmpty() ? "" : LikePatterns.containsIgnoreCase(trimmed);
        // An exact ISBN match is only possible when the text is itself a valid ISBN; '' never matches a row.
        String isbn = trimmed.isEmpty() ? "" : Isbn.canonicalise(trimmed).orElse("");
        int activeMode = active == null ? 0 : (active ? 1 : 2);

        return PageResponse.of(books.search(pattern, isbn, categoryId == null ? 0L : categoryId, activeMode,
                PageRequest.of(page, size, sort)).map(AdminBookSummaryResponse::from));
    }

    private AdminCategory findCategory(Long categoryId, List<FieldProblem> problems) {
        if (categoryId == null) {
            return null;
        }
        return categories.findById(categoryId).orElseGet(() -> {
            problems.add(new FieldProblem("categoryId", "does not refer to an existing category"));
            return null;
        });
    }

    private Set<AdminAuthor> findAuthors(List<Long> authorIds, List<FieldProblem> problems) {
        if (authorIds == null || authorIds.isEmpty() || authorIds.stream().anyMatch(java.util.Objects::isNull)) {
            return Set.of();
        }
        Set<Long> distinct = new LinkedHashSet<>(authorIds);
        if (distinct.size() != authorIds.size()) {
            problems.add(new FieldProblem("authorIds", "must not contain duplicates"));
            return Set.of();
        }
        List<AdminAuthor> found = authors.findAllById(distinct);
        if (found.size() != distinct.size()) {
            problems.add(new FieldProblem("authorIds", "must all refer to existing authors"));
            return Set.of();
        }
        return new LinkedHashSet<>(found);
    }

    private void saveAndFlushMappingDuplicates(AdminBook book) {
        try {
            books.saveAndFlush(book);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintNames.violated(e, ConstraintNames.BOOK_ISBN)) {
                throw duplicateIsbn();
            }
            throw e;
        }
    }

    private static void failIfInvalid(List<FieldProblem> problems) {
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.", problems);
        }
    }

    private static ApiException duplicateIsbn() {
        return new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A book with this ISBN already exists.");
    }

    private static ApiException staleVersion() {
        return new ApiException(ErrorCode.CONCURRENT_UPDATE,
                "The book was changed by someone else. Reload it and try again.");
    }
}
