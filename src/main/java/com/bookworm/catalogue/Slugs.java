package com.bookworm.catalogue;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Category slug generation. A slug is lower-case ASCII letters and digits separated by single hyphens, which is
 * exactly what the database CHECK {@code ck_categories_slug_format} requires. It is generated once, when the
 * category is created, and never changes afterwards.
 */
final class Slugs {

    static final int MAX_LENGTH = 100;

    private Slugs() {
    }

    /** The base slug for a name, or an empty string when nothing usable remains (for example a non-Latin name). */
    static String slugify(String name) {
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        String slug = decomposed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return truncate(slug, MAX_LENGTH);
    }

    /** Attempt 1 is the base slug; attempt n (2, 3, ...) is {@code base-n}, truncated so it still fits. */
    static String candidate(String base, int attempt) {
        if (attempt <= 1) {
            return base;
        }
        String suffix = "-" + attempt;
        return truncate(base, MAX_LENGTH - suffix.length()) + suffix;
    }

    private static String truncate(String slug, int max) {
        if (slug.length() <= max) {
            return slug;
        }
        return slug.substring(0, max).replaceAll("-+$", "");
    }
}
