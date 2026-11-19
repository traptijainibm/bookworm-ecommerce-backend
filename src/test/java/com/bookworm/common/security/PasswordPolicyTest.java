package com.bookworm.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void acceptsAPasswordOfAtLeastTwelveCharacters() {
        assertThat(PasswordPolicy.violation("twelve chars", "a@example.test")).isNull();
    }

    @Test
    void rejectsNullBlankAndShortPasswords() {
        assertThat(PasswordPolicy.violation(null, "a@example.test")).isEqualTo("must not be blank");
        assertThat(PasswordPolicy.violation("            ", "a@example.test")).isEqualTo("must not be blank");
        assertThat(PasswordPolicy.violation("elevenchars", "a@example.test"))
                .isEqualTo("must be at least 12 characters");
    }

    @Test
    void enforcesTheBcryptLimitInBytesNotCharacters() {
        assertThat(PasswordPolicy.violation("a".repeat(72), null)).isNull();
        assertThat(PasswordPolicy.violation("a".repeat(73), null)).contains("at most 72 bytes");
        // 25 euro signs are 25 characters but 75 UTF-8 bytes.
        assertThat(PasswordPolicy.violation("\u20ac".repeat(25), null)).contains("at most 72 bytes");
        assertThat(PasswordPolicy.exceedsBcryptLimit("\u20ac".repeat(25))).isTrue();
        assertThat(PasswordPolicy.exceedsBcryptLimit("a".repeat(72))).isFalse();
    }

    @Test
    void rejectsAPasswordEqualToTheEmailIgnoringCase() {
        assertThat(PasswordPolicy.violation("Reader.One@Example.test", "reader.one@example.test"))
                .isEqualTo("must not be the same as the email address");
    }

    @Test
    void requireThrowsAFieldLevelValidationError() {
        assertThatThrownBy(() -> PasswordPolicy.require("password", "short", null))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getFieldProblems()).singleElement()
                            .satisfies(p -> assertThat(p.field()).isEqualTo("password"));
                });
    }
}
