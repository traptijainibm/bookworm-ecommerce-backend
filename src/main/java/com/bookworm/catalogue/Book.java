package com.bookworm.catalogue;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Immutable;

/**
 * Read-only in Phase 1 ({@code @Immutable}): nothing in this phase can change a book or its stock.
 * The {@code version}, {@code created_at} and {@code updated_at} columns exist in the schema but are
 * mapped only when the admin API (Phase 3) needs them.
 */
@Entity
@Immutable
@Table(name = "books")
public class Book {

    @Id
    private Long id;

    private String isbn;

    private String title;

    private String description;

    private BigDecimal price;

    private String publisher;

    private LocalDate publishedDate;

    private String coverUrl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id")
    private Category category;

    private int stockQuantity;

    private boolean active;

    @ManyToMany
    @JoinTable(name = "book_authors",
            joinColumns = @JoinColumn(name = "book_id"),
            inverseJoinColumns = @JoinColumn(name = "author_id"))
    @OrderBy("name ASC, id ASC")
    @BatchSize(size = 50)
    private List<Author> authors;

    protected Book() {
    }

    public Long getId() {
        return id;
    }

    public String getIsbn() {
        return isbn;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getPublisher() {
        return publisher;
    }

    public LocalDate getPublishedDate() {
        return publishedDate;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public Category getCategory() {
        return category;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public boolean isActive() {
        return active;
    }

    public List<Author> getAuthors() {
        return authors;
    }
}
