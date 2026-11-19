package com.bookworm.common.persistence;

import java.util.Locale;

/**
 * Builds SQL {@code LIKE} patterns from user input. Wildcard characters typed by the user are
 * escaped so they match literally, and the pattern is lower-cased so it can be compared with
 * {@code lower(column)}, which is what the trigram indexes are built on.
 */
public final class LikePatterns {

    /** Escape character; must be passed as the {@code ESCAPE} clause of every LIKE using these patterns. */
    public static final char ESCAPE = '\\';

    private LikePatterns() {
    }

    /** Returns {@code %<escaped lower-cased input>%}. */
    public static String containsIgnoreCase(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2).append('%');
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == ESCAPE || c == '%' || c == '_') {
                sb.append(ESCAPE);
            }
            sb.append(c);
        }
        return sb.append('%').toString();
    }
}
