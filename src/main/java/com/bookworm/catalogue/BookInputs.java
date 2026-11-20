package com.bookworm.catalogue;

import com.bookworm.common.error.ApiException.FieldProblem;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * Server-side validation and normalisation of the book fields that bean validation cannot express
 * (ISBN checksum, cover URL rules, control characters). Problems are collected so a client sees them together.
 */
final class BookInputs {

    private BookInputs() {
    }

    /** Returns the normalised values; every problem found is added to {@code problems}. */
    static BookValues validate(BookFields in, List<FieldProblem> problems) {
        String isbn = Isbn.canonicalise(in.isbn()).orElse(null);
        if (isbn == null) {
            problems.add(new FieldProblem("isbn", "must be a valid ISBN-10 or ISBN-13"));
        }

        String title = in.title() == null ? "" : in.title().strip();
        if (title.isEmpty()) {
            problems.add(new FieldProblem("title", "must not be blank"));
        } else if (hasControlCharacter(title, false)) {
            problems.add(new FieldProblem("title", "contains characters that are not allowed"));
        }

        String description = blankToNull(in.description());
        if (description != null && hasControlCharacter(description, true)) {
            problems.add(new FieldProblem("description", "contains characters that are not allowed"));
        }

        String publisher = blankToNull(in.publisher());
        if (publisher != null && hasControlCharacter(publisher, false)) {
            problems.add(new FieldProblem("publisher", "contains characters that are not allowed"));
        }

        String coverUrl = blankToNull(in.coverUrl());
        if (coverUrl != null && !isAcceptableCoverUrl(coverUrl)) {
            problems.add(new FieldProblem("coverUrl", "must be an https URL without credentials"));
        }

        return new BookValues(isbn, title, description, in.price(), publisher, in.publishedDate(), coverUrl);
    }

    /** https only, a host, no user info, no whitespace or control characters. */
    static boolean isAcceptableCoverUrl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= ' ' || Character.isISOControl(c)) {
                return false;
            }
        }
        try {
            URI uri = new URI(value);
            return uri.getScheme() != null && "https".equals(uri.getScheme().toLowerCase(Locale.ROOT))
                    && uri.getUserInfo() == null
                    && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean hasControlCharacter(String value, boolean allowLineBreaksAndTabs) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && !(allowLineBreaksAndTabs && (c == '\n' || c == '\r' || c == '\t'))) {
                return true;
            }
        }
        return false;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
