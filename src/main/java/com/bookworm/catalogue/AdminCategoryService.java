package com.bookworm.catalogue;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Admin category use cases. Slugs are generated on create and never change. */
@Service
public class AdminCategoryService {

    private static final Logger log = LoggerFactory.getLogger(AdminCategoryService.class);
    private static final int MAX_SLUG_ATTEMPTS = 100;

    private final AdminCategoryRepository categories;
    private final AdminBookRepository books;
    private final CategoryWriter writer;

    public AdminCategoryService(AdminCategoryRepository categories, AdminBookRepository books,
                                CategoryWriter writer) {
        this.categories = categories;
        this.books = books;
        this.writer = writer;
    }

    /**
     * Deliberately not transactional: each insert attempt runs in its own transaction inside
     * {@link CategoryWriter}, so a slug collision can be retried.
     */
    @Transactional(propagation = Propagation.NEVER)
    public AdminCategoryResponse create(Long adminId, CategoryRequest request) {
        String name = CatalogueNames.require("name", request.name(), CatalogueNames.MAX_CATEGORY);
        if (categories.existsByNameIgnoreCase(name)) {
            throw duplicateName();
        }
        String base = Slugs.slugify(name);
        if (base.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                    List.of(new ApiException.FieldProblem("name",
                            "must contain at least one letter or digit from the Latin alphabet")));
        }
        for (int attempt = 1; attempt <= MAX_SLUG_ATTEMPTS; attempt++) {
            try {
                AdminCategory created = writer.insert(name, Slugs.candidate(base, attempt));
                log.info("admin_audit action=category.create categoryId={} adminId={}", created.getId(), adminId);
                return AdminCategoryResponse.from(created, 0);
            } catch (DataIntegrityViolationException e) {
                if (ConstraintNames.violatedAny(e, ConstraintNames.CATEGORY_NAME,
                        ConstraintNames.CATEGORY_NAME_EXACT)) {
                    throw duplicateName();
                }
                if (!ConstraintNames.violated(e, ConstraintNames.CATEGORY_SLUG)) {
                    throw e;
                }
                // slug taken by another category: try the next candidate in a new transaction
            }
        }
        throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A unique slug could not be generated for this name.");
    }

    @Transactional
    public AdminCategoryResponse rename(Long adminId, Long id, CategoryRequest request) {
        AdminCategory category = categories.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Category not found."));
        String name = CatalogueNames.require("name", request.name(), CatalogueNames.MAX_CATEGORY);
        if (categories.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw duplicateName();
        }
        category.rename(name);
        try {
            categories.flush();
        } catch (DataIntegrityViolationException e) {
            if (ConstraintNames.violatedAny(e, ConstraintNames.CATEGORY_NAME,
                        ConstraintNames.CATEGORY_NAME_EXACT)) {
                throw duplicateName();
            }
            throw e;
        }
        log.info("admin_audit action=category.update categoryId={} adminId={}", id, adminId);
        return AdminCategoryResponse.from(category, bookCount(id));
    }

    @Transactional(readOnly = true)
    public AdminCategoryResponse get(Long id) {
        AdminCategory category = categories.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Category not found."));
        return AdminCategoryResponse.from(category, bookCount(id));
    }

    @Transactional(readOnly = true)
    public List<AdminCategoryResponse> list() {
        List<AdminCategory> all = categories.findAllByOrderByNameAscIdAsc();
        if (all.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> counts = books.countByCategory(all.stream().map(AdminCategory::getId).toList())
                .stream().collect(Collectors.toMap(IdCount::id, IdCount::count));
        return all.stream().map(c -> AdminCategoryResponse.from(c, counts.getOrDefault(c.getId(), 0L))).toList();
    }

    private long bookCount(Long categoryId) {
        return books.countByCategory(List.of(categoryId)).stream().mapToLong(IdCount::count).sum();
    }

    private static ApiException duplicateName() {
        return new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A category with this name already exists.");
    }
}
