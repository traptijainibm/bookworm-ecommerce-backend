package com.bookworm.catalogue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The fields shared by book create and update requests. There is deliberately no stock or active field. */
interface BookFields {

    String isbn();

    String title();

    String description();

    BigDecimal price();

    String publisher();

    LocalDate publishedDate();

    String coverUrl();

    Long categoryId();

    List<Long> authorIds();
}
