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
 * Read-only queries. Every query is parameterized; user text only ever reaches the database as a bound
 * {@code LIKE} pattern built by {@link com.bookworm.common.persistence.LikePatterns}.
 * Only active books are ever returned.
 */
public interface BookRepository extends JpaRepository<Book, Long> {

    /**
     * Pass {@code ""} for {@code categorySlug} and/or {@code pattern} when the filter is not used
     * (an empty string avoids PostgreSQL's untyped-null parameter problem).
     */
    @Query(value = """
            select b from Book b join fetch b.category c
            where b.active = true
              and (:categorySlug = '' or c.slug = :categorySlug)
              and (:pattern = ''
                   or lower(b.title) like :pattern escape '\\'
                   or exists (select 1 from b.authors a where lower(a.name) like :pattern escape '\\'))
            """,
            countQuery = """
            select count(b) from Book b join b.category c
            where b.active = true
              and (:categorySlug = '' or c.slug = :categorySlug)
              and (:pattern = ''
                   or lower(b.title) like :pattern escape '\\'
                   or exists (select 1 from b.authors a where lower(a.name) like :pattern escape '\\'))
            """)
    Page<Book> search(@Param("categorySlug") String categorySlug,
                      @Param("pattern") String pattern,
                      Pageable pageable);

    @Query("select b from Book b join fetch b.category where b.id = :id and b.active = true")
    Optional<Book> findActiveById(@Param("id") Long id);

    /** Active books (other than {@code bookId}) sharing at least one author with it, by title then id. */
    @Query("""
            select b from Book b join fetch b.category
            where b.active = true and b.id <> :bookId
              and exists (select 1 from b.authors ba
                          where ba.id in (select sa.id from Book src join src.authors sa where src.id = :bookId))
            order by b.title asc, b.id asc
            """)
    List<Book> findSharingAuthor(@Param("bookId") Long bookId, Pageable limit);

    /** Active books in a category, excluding the given ids (must be non-empty), by title then id. */
    @Query("""
            select b from Book b join fetch b.category c
            where b.active = true and c.id = :categoryId and b.id not in :excludedIds
            order by b.title asc, b.id asc
            """)
    List<Book> findInCategoryExcluding(@Param("categoryId") Long categoryId,
                                       @Param("excludedIds") Collection<Long> excludedIds,
                                       Pageable limit);
}
