package com.bookworm.catalogue;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Read-only in Phase 1; writes arrive with the admin API in Phase 3. */
@Entity
@Immutable
@Table(name = "categories")
public class Category {

    @Id
    private Long id;

    private String name;

    private String slug;

    protected Category() {
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
