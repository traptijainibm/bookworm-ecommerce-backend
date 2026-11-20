package com.bookworm.catalogue;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only lookup of books by id for other features (cart, wishlist). Unlike the public catalogue it
 * <strong>includes inactive books</strong>, because a cart or wishlist entry may refer to a book deactivated after
 * it was added. It never writes, never reserves stock, and returns {@link BookInfo} records, never entities.
 * It builds on the existing {@link BookRepository}; no public catalogue query or response is changed.
 */
@Service
@Transactional(readOnly = true)
public class BookLookupService {

    private final BookRepository bookRepository;

    public BookLookupService(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    /** The books that exist for the given ids (active or not), keyed by id. Unknown ids are simply absent. */
    public Map<Long, BookInfo> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, BookInfo> found = new LinkedHashMap<>();
        for (Book book : bookRepository.findAllById(new LinkedHashSet<>(ids))) {
            found.put(book.getId(), BookInfo.from(book));
        }
        return Collections.unmodifiableMap(found);
    }

    /** One book by id, active or not; empty when no such book exists. */
    public Optional<BookInfo> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return bookRepository.findById(id).map(BookInfo::from);
    }
}
