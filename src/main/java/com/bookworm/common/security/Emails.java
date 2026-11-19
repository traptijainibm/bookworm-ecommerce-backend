package com.bookworm.common.security;

import java.util.Locale;

/**
 * Email rules shared by registration and the admin bootstrap. The pattern is deliberately strict and uses
 * bounded, unambiguous groups (labels cannot contain a dot), so matching is linear and cannot be used for
 * regular-expression denial of service.
 */
public final class Emails {

    public static final int MAX_LENGTH = 254;

    public static final String REGEX =
            "^[A-Za-z0-9._%+-]{1,64}@([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$";

    private Emails() {
    }

    /** Canonical stored form: trimmed and lower-cased (the database also enforces lower case). */
    public static String normalise(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String email) {
        return email != null && email.length() <= MAX_LENGTH && email.matches(REGEX);
    }
}
