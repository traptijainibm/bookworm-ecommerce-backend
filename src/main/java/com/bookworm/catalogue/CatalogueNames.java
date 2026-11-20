package com.bookworm.catalogue;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import java.text.Normalizer;
import java.util.List;

/**
 * Validation and normalisation of category and author names.
 * <ol>
 *   <li>Rejected outright (checked on the raw input): control characters (including tab and newline), format
 *       characters (zero-width, bidirectional overrides), private-use, unassigned and lone surrogate code points.</li>
 *   <li>Normalised: trimmed, runs of Unicode whitespace collapsed to one space, then NFC.</li>
 *   <li>Rejected when the NFKC form differs from the NFC form (full-width letters, ligatures, circled digits...),
 *       so look-alike duplicates cannot pass the case-insensitive unique index.</li>
 *   <li>Only letters, marks, decimal digits, space and {@code . , ' ’ - ( ) & / : + !} are allowed, with at least one
 *       letter or digit. Length is counted in code points.</li>
 * </ol>
 * Known limitation: mixed-script homoglyphs (for example Cyrillic "а" and Latin "a") are not detected.
 */
final class CatalogueNames {

    static final int MAX_CATEGORY = 100;
    static final int MAX_AUTHOR = 200;

    private static final String ALLOWED_PUNCTUATION = ".,'’-()&/:+!";

    private CatalogueNames() {
    }

    /** Returns the normalised name, or throws {@code VALIDATION_FAILED} with a field error. */
    static String require(String field, String raw, int maxCodePoints) {
        String problem = violation(raw, maxCodePoints);
        if (problem != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                    List.of(new ApiException.FieldProblem(field, problem)));
        }
        return normalise(raw);
    }

    /** Trimmed, whitespace-collapsed, NFC form. Call {@link #violation} first for untrusted input. */
    static String normalise(String raw) {
        String collapsed = raw.replaceAll("[\\s\\p{Z}]+", " ").strip();
        return Normalizer.normalize(collapsed, Normalizer.Form.NFC);
    }

    /** A client-safe description of the first violated rule, or {@code null} when the name is acceptable. */
    static String violation(String raw, int maxCodePoints) {
        if (raw == null || raw.isBlank()) {
            return "must not be blank";
        }
        // Reject before normalising: the raw input could hide a forbidden code point from later steps.
        if (raw.length() > maxCodePoints * 4) {
            return "must be at most " + maxCodePoints + " characters";
        }
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE
                    || type == Character.UNASSIGNED || type == Character.SURROGATE) {
                return "contains characters that are not allowed";
            }
        }
        String name = normalise(raw);
        if (name.isEmpty()) {
            return "must not be blank";
        }
        if (name.codePointCount(0, name.length()) > maxCodePoints) {
            return "must be at most " + maxCodePoints + " characters";
        }
        if (!Normalizer.normalize(name, Normalizer.Form.NFKC).equals(name)) {
            return "contains compatibility characters that are not allowed";
        }
        boolean hasLetterOrDigit = false;
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp) || Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER) {
                hasLetterOrDigit = true;
            } else if (!isMark(cp) && cp != ' ' && ALLOWED_PUNCTUATION.indexOf(cp) < 0) {
                return "contains characters that are not allowed";
            }
        }
        return hasLetterOrDigit ? null : "must contain at least one letter or digit";
    }

    private static boolean isMark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }
}
