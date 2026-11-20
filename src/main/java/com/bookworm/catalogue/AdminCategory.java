package com.bookworm.catalogue;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Writable mapping of {@code categories} used only by the admin API. The public {@link Category} stays an
 * {@code @Immutable} read-only mapping. The slug is set once on creation and has no setter.
 */
@Entity
@Table(name = "categories")
public class AdminCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String slug;

    protected AdminCategory() {
    }

    static AdminCategory create(String name, String slug) {
        AdminCategory category = new AdminCategory();
        category.name = name;
        category.slug = slug;
        return category;
    }

    void rename(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }
}
