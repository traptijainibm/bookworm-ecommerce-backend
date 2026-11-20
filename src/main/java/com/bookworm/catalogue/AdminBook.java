package com.bookworm.catalogue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import org.hibernate.annotations.BatchSize;

/**
 * Writable mapping of {@code books} used only by the admin API; the public {@link Book} stays {@code @Immutable}.
 * <p>
 * {@code stockQuantity} is mapped read-only ({@code insertable = false, updatable = false}) and has no setter,
 * so no admin catalogue write can ever change stock: inserts use the database default of 0 and updates never
 * include the column. Stock changes belong to the inventory/checkout phases.
 */
@Entity
@Table(name = "books")
public class AdminBook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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
    private AdminCategory category;

    @Column(insertable = false, updatable = false)
    private int stockQuantity;

    private boolean active;

    @Version
    private Long version;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @ManyToMany
    @JoinTable(name = "book_authors",
            joinColumns = @JoinColumn(name = "book_id"),
            inverseJoinColumns = @JoinColumn(name = "author_id"))
    @OrderBy("name ASC, id ASC")
    @BatchSize(size = 50)
    private Set<AdminAuthor> authors = new LinkedHashSet<>();

    protected AdminBook() {
    }

    /** A new book is always inactive; the admin activates it explicitly. */
    static AdminBook create(BookValues values, AdminCategory category, Set<AdminAuthor> authors) {
        AdminBook book = new AdminBook();
        book.active = false;
        book.createdAt = Instant.now();
        book.updatedAt = book.createdAt;
        book.apply(values, category, authors);
        return book;
    }

    void apply(BookValues values, AdminCategory category, Set<AdminAuthor> newAuthors) {
        this.isbn = values.isbn();
        this.title = values.title();
        this.description = values.description();
        this.price = values.price();
        this.publisher = values.publisher();
        this.publishedDate = values.publishedDate();
        this.coverUrl = values.coverUrl();
        this.category = category;
        if (!authors.equals(newAuthors)) {
            authors.clear();
            authors.addAll(newAuthors);
        }
    }

    void setActive(boolean active) {
        this.active = active;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
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

    public AdminCategory getCategory() {
        return category;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public boolean isActive() {
        return active;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Set<AdminAuthor> getAuthors() {
        return authors;
    }
}
