package com.bookworm.catalogue;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import com.bookworm.common.persistence.LikePatterns;
import com.bookworm.common.web.PageResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin author use cases. Last writer wins on rename (authors have no version column). */
@Service
public class AdminAuthorService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthorService.class);

    /** Public sort keys mapped to entity properties. */
    static final Map<String, String> SORT_FIELDS = Map.of("name", "name", "id", "id");

    private final AdminAuthorRepository authors;
    private final AdminBookRepository books;

    public AdminAuthorService(AdminAuthorRepository authors, AdminBookRepository books) {
        this.authors = authors;
        this.books = books;
    }

    @Transactional
    public AdminAuthorResponse create(Long adminId, AuthorRequest request) {
        String name = CatalogueNames.require("name", request.name(), CatalogueNames.MAX_AUTHOR);
        if (authors.existsByNameIgnoreCase(name)) {
            throw duplicateName();
        }
        AdminAuthor author = AdminAuthor.create(name);
        try {
            authors.saveAndFlush(author);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintNames.violatedAny(e, ConstraintNames.AUTHOR_NAME,
                    ConstraintNames.AUTHOR_NAME_EXACT)) {
                throw duplicateName();
            }
            throw e;
        }
        log.info("admin_audit action=author.create authorId={} adminId={}", author.getId(), adminId);
        return AdminAuthorResponse.from(author, 0);
    }

    @Transactional
    public AdminAuthorResponse rename(Long adminId, Long id, AuthorRequest request) {
        AdminAuthor author = authors.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Author not found."));
        String name = CatalogueNames.require("name", request.name(), CatalogueNames.MAX_AUTHOR);
        if (authors.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw duplicateName();
        }
        author.rename(name);
        try {
            authors.flush();
        } catch (DataIntegrityViolationException e) {
            if (ConstraintNames.violatedAny(e, ConstraintNames.AUTHOR_NAME,
                    ConstraintNames.AUTHOR_NAME_EXACT)) {
                throw duplicateName();
            }
            throw e;
        }
        log.info("admin_audit action=author.update authorId={} adminId={}", id, adminId);
        return AdminAuthorResponse.from(author, bookCounts(List.of(id)).getOrDefault(id, 0L));
    }

    @Transactional(readOnly = true)
    public AdminAuthorResponse get(Long id) {
        AdminAuthor author = authors.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Author not found."));
        return AdminAuthorResponse.from(author, bookCounts(List.of(id)).getOrDefault(id, 0L));
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminAuthorResponse> search(String query, int page, int size, String sortField,
                                                    Sort.Direction direction) {
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

        Page<AdminAuthor> result = authors.search(pattern, PageRequest.of(page, size, sort));
        Map<Long, Long> counts = result.isEmpty() ? Map.of()
                : bookCounts(result.getContent().stream().map(AdminAuthor::getId).toList());
        return PageResponse.of(result.map(a -> AdminAuthorResponse.from(a, counts.getOrDefault(a.getId(), 0L))));
    }

    private Map<Long, Long> bookCounts(List<Long> authorIds) {
        return books.countByAuthor(authorIds).stream().collect(Collectors.toMap(IdCount::id, IdCount::count));
    }

    private static ApiException duplicateName() {
        return new ApiException(ErrorCode.DUPLICATE_RESOURCE, "An author with this name already exists.");
    }
}
