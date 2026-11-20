package com.bookworm.catalogue;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminAuthorRepository extends JpaRepository<AdminAuthor, Long> {

    /** {@code pattern} is {@code ''} (no filter) or a {@code LIKE} pattern from {@code LikePatterns}. */
    @Query("select a from AdminAuthor a where :pattern = '' or lower(a.name) like :pattern escape '\\'")
    Page<AdminAuthor> search(@Param("pattern") String pattern, Pageable pageable);

    /** Case-insensitive, matching the {@code ux_authors_name_ci} index on {@code lower(name)}. */
    @Query("select count(a) > 0 from AdminAuthor a where lower(a.name) = lower(:name)")
    boolean existsByNameIgnoreCase(@Param("name") String name);

    @Query("select count(a) > 0 from AdminAuthor a where lower(a.name) = lower(:name) and a.id <> :id")
    boolean existsByNameIgnoreCaseAndIdNot(@Param("name") String name, @Param("id") Long id);
}
