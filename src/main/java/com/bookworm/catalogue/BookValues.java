package com.bookworm.catalogue;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Validated, normalised book fields shared by create and update (never carries stock or the active flag). */
record BookValues(String isbn, String title, String description, BigDecimal price, String publisher,
                  LocalDate publishedDate, String coverUrl) {
}
