package com.bookworm.catalogue;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Admin book queries (active and inactive books). Every query is parameterized. PostgreSQL cannot infer the
 * type of a null parameter, so "no filter" is a sentinel: {@code ''} for text, {@code 0} for the category and
 * {@code 0} for the active mode.
 */
public interface AdminBookRepository extends JpaRepository<AdminBook, Long> {

    /**
     * @param pattern    {@code ''} or a {@code LIKE} pattern from {@code LikePatterns.containsIgnoreCase}
     * @param isbn       {@code ''} or a canonical ISBN-13 that also matches exactly
     * @param categoryId {@code 0} for any category
     * @param activeMode 0 = any, 1 = active only, 2 = inactive only
     */
    @Query(value = """
            select b from AdminBook b join fetch b.category c
            where (:categoryId = 0L or c.id = :categoryId)
              and (:activeMode = 0 or (:activeMode = 1 and b.active = true) or (:activeMode = 2 and b.active = false))
              and (:pattern = ''
                   or lower(b.title) like :pattern escape '\\'
                   or b.isbn = :isbn
                   or exists (select 1 from b.authors a where lower(a.name) like :pattern escape '\\'))
            """,
            countQuery = """
            select count(b) from AdminBook b join b.category c
            where (:categoryId = 0L or c.id = :categoryId)
              and (:activeMode = 0 or (:activeMode = 1 and b.active = true) or (:activeMode = 2 and b.active = false))
              and (:pattern = ''
                   or lower(b.title) like :pattern escape '\\'
                   or b.isbn = :isbn
                   or exists (select 1 from b.authors a where lower(a.name) like :pattern escape '\\'))
            """)
    Page<AdminBook> search(@Param("pattern") String pattern,
                           @Param("isbn") String isbn,
                           @Param("categoryId") long categoryId,
                           @Param("activeMode") int activeMode,
                           Pageable pageable);

    @Query("select b from AdminBook b join fetch b.category where b.id = :id")
    Optional<AdminBook> findDetailById(@Param("id") Long id);

    boolean existsByIsbn(String isbn);

    boolean existsByIsbnAndIdNot(String isbn, Long id);

    /** Number of books (active or not) per category, for the given category ids. */
    @Query("select new com.bookworm.catalogue.IdCount(b.category.id, count(b)) from AdminBook b "
            + "where b.category.id in :ids group by b.category.id")
    List<IdCount> countByCategory(@Param("ids") Collection<Long> ids);

    /** Number of books (active or not) per author, for the given author ids. */
    @Query("select new com.bookworm.catalogue.IdCount(a.id, count(b)) from AdminBook b join b.authors a "
            + "where a.id in :ids group by a.id")
    List<IdCount> countByAuthor(@Param("ids") Collection<Long> ids);
}
