package com.bookworm.catalogue;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies WHICH database constraint a failed write violated, so only the expected unique violations are
 * reported as "duplicate" and every other integrity failure still reaches the generic handler.
 * <p>
 * The name comes from Hibernate's {@code ConstraintViolationException} when it has one, otherwise from the
 * PostgreSQL message text ({@code ... violates unique constraint "name"}), found anywhere in the cause chain.
 * The constraint name is only ever used for matching; it is never returned to a client.
 */
final class ConstraintNames {

    static final String BOOK_ISBN = "uk_books_isbn";
    static final String AUTHOR_NAME = "ux_authors_name_ci";
    static final String CATEGORY_NAME = "ux_categories_name_ci";
    static final String CATEGORY_SLUG = "uk_categories_slug";
    /** The original exact-match unique constraints can fire before the case-insensitive indexes. */
    static final String AUTHOR_NAME_EXACT = "uk_authors_name";
    static final String CATEGORY_NAME_EXACT = "uk_categories_name";

    private static final Pattern IN_MESSAGE = Pattern.compile("constraint \"([^\"]+)\"");

    private ConstraintNames() {
    }

    /** The violated constraint's name (lower-case), if it can be determined. */
    static Optional<String> of(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 10; t = t.getCause(), depth++) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve
                    && cve.getConstraintName() != null) {
                return Optional.of(cve.getConstraintName().toLowerCase(Locale.ROOT));
            }
            String message = t.getMessage();
            if (message != null) {
                Matcher matcher = IN_MESSAGE.matcher(message);
                if (matcher.find()) {
                    return Optional.of(matcher.group(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return Optional.empty();
    }

    static boolean violated(Throwable error, String constraint) {
        return of(error).filter(constraint::equals).isPresent();
    }

    static boolean violatedAny(Throwable error, String... constraints) {
        for (String constraint : constraints) {
            if (violated(error, constraint)) {
                return true;
            }
        }
        return false;
    }
}
