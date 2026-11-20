package com.bookworm.catalogue;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts one category in its own transaction. It is a separate bean so the {@code REQUIRES_NEW} boundary is
 * a real proxy call: a unique-violation on the slug rolls back only this attempt, and the caller can retry
 * with the next slug candidate in a fresh transaction (a failed statement poisons the PostgreSQL transaction).
 */
@Component
public class CategoryWriter {

    private final AdminCategoryRepository repository;

    public CategoryWriter(AdminCategoryRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AdminCategory insert(String name, String slug) {
        return repository.saveAndFlush(AdminCategory.create(name, slug));
    }
}
