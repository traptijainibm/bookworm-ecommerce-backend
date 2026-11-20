package com.bookworm.catalogue;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminCategoryRepository extends JpaRepository<AdminCategory, Long> {

    List<AdminCategory> findAllByOrderByNameAscIdAsc();

    /** Case-insensitive, matching the {@code ux_categories_name_ci} index on {@code lower(name)}. */
    @Query("select count(c) > 0 from AdminCategory c where lower(c.name) = lower(:name)")
    boolean existsByNameIgnoreCase(@Param("name") String name);

    @Query("select count(c) > 0 from AdminCategory c where lower(c.name) = lower(:name) and c.id <> :id")
    boolean existsByNameIgnoreCaseAndIdNot(@Param("name") String name, @Param("id") Long id);
}
