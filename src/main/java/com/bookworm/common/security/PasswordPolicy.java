package com.bookworm.common.security;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Password rules: at least 12 characters, at most 72 UTF-8 bytes (the BCrypt limit; longer input would be
 * silently truncated or rejected), not blank and not equal to the account email. No composition rules.
 */
public final class PasswordPolicy {

    public static final int MIN_CHARACTERS = 12;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** Returns a client-safe description of the first violated rule, or {@code null} when the password is acceptable. */
    public static String violation(String password, String email) {
        if (password == null || password.isBlank()) {
            return "must not be blank";
        }
        if (password.codePointCount(0, password.length()) < MIN_CHARACTERS) {
            return "must be at least " + MIN_CHARACTERS + " characters";
        }
        if (utf8Length(password) > MAX_BYTES) {
            return "must be at most " + MAX_BYTES + " bytes when UTF-8 encoded";
        }
        if (email != null && password.equalsIgnoreCase(email)) {
            return "must not be the same as the email address";
        }
        return null;
    }

    /** Throws a field-level {@code VALIDATION_FAILED} error when the password breaks the policy. */
    public static void require(String field, String password, String email) {
        String problem = violation(password, email);
        if (problem != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                    List.of(new ApiException.FieldProblem(field, problem)));
        }
    }

    public static boolean exceedsBcryptLimit(String password) {
        return password != null && utf8Length(password) > MAX_BYTES;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
