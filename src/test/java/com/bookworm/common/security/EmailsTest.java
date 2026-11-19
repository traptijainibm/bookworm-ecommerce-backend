package com.bookworm.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailsTest {

    @Test
    void normaliseTrimsAndLowerCases() {
        assertThat(Emails.normalise("  Reader.One@Example.TEST ")).isEqualTo("reader.one@example.test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"reader@example.test", "a.b+c@sub.example.co", "x_y%z@example-site.org"})
    void acceptsOrdinaryAddresses(String email) {
        assertThat(Emails.isValid(email)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "plain", "@example.test", "a@", "a@b", "a b@example.test",
            "a'; drop table users;--@example.test", "a@example..test", "a@-.test", "<script>@example.test",
            "a@example.test\nbcc:evil@example.test"})
    void rejectsMalformedOrDangerousAddresses(String email) {
        assertThat(Emails.isValid(email)).isFalse();
    }

    @Test
    void rejectsAddressesLongerThan254CharactersWithoutBacktrackingBlowUp() {
        String longLocal = "a".repeat(65) + "@example.test";
        String huge = "a".repeat(300) + "@example.test";
        String pathological = "a@" + "a-".repeat(2000) + "!";

        assertThat(Emails.isValid(longLocal)).isFalse();
        assertThat(Emails.isValid(huge)).isFalse();
        assertThat(Emails.isValid(pathological)).isFalse();
    }

    @Test
    void nullIsInvalid() {
        assertThat(Emails.isValid(null)).isFalse();
        assertThat(Emails.normalise(null)).isNull();
    }
}
