package com.bookworm.catalogue;

import java.util.Optional;

/**
 * ISBN rules for the admin API. Input may be an ISBN-10 or an ISBN-13, with ASCII spaces and hyphens, and a
 * lower-case {@code x} check character. The checksum is verified here (the database only checks the shape) and
 * the stored form is always the canonical 13-digit ISBN-13, so one book can only exist under one ISBN.
 */
final class Isbn {

    static final int MAX_RAW_LENGTH = 20;

    private Isbn() {
    }

    /** The canonical ISBN-13, or empty when the input is not a valid ISBN-10 or ISBN-13. */
    static Optional<String> canonicalise(String raw) {
        if (raw == null || raw.length() > MAX_RAW_LENGTH) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ' ' || c == '-') {
                continue;
            }
            sb.append(c == 'x' ? 'X' : c);
        }
        String value = sb.toString();
        if (value.length() == 13 && allDigits(value, 13)) {
            boolean prefixOk = value.startsWith("978") || value.startsWith("979");
            boolean checkOk = isbn13CheckDigit(value) == value.charAt(12) - '0';
            return prefixOk && checkOk ? Optional.of(value) : Optional.empty();
        }
        if (value.length() == 10 && allDigits(value, 9) && (isAsciiDigit(value.charAt(9)) || value.charAt(9) == 'X')) {
            if (!isbn10ChecksumValid(value)) {
                return Optional.empty();
            }
            String twelve = "978" + value.substring(0, 9);
            return Optional.of(twelve + isbn13CheckDigit(twelve));
        }
        return Optional.empty();
    }

    /** True when the first {@code count} characters are ASCII digits (not other Unicode digits). */
    private static boolean allDigits(String value, int count) {
        for (int i = 0; i < count; i++) {
            if (!isAsciiDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isbn10ChecksumValid(String value) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char c = value.charAt(i);
            int d = c == 'X' ? 10 : c - '0';
            sum += d * (10 - i);
        }
        return sum % 11 == 0;
    }

    /** Check digit computed from the first twelve digits of {@code value} (weights 1, 3, 1, 3, ...). */
    private static int isbn13CheckDigit(String value) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (value.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return (10 - sum % 10) % 10;
    }
}
