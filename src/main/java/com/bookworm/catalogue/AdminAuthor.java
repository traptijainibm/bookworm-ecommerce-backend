package com.bookworm.catalogue;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Writable mapping of {@code authors} used only by the admin API (the public {@link Author} stays read-only). */
@Entity
@Table(name = "authors")
public class AdminAuthor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    protected AdminAuthor() {
    }

    static AdminAuthor create(String name) {
        AdminAuthor author = new AdminAuthor();
        author.name = name;
        return author;
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
}
