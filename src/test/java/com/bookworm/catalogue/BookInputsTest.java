package com.bookworm.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookworm.common.error.ApiException.FieldProblem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BookInputsTest {

    private static BookFields fields(String isbn, String title, String description, String publisher,
                                     String coverUrl) {
        return new BookCreateRequest(isbn, title, description, new BigDecimal("1.00"), publisher, null, coverUrl, 1L,
                List.of(1L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://img.example.test/a.jpg", "HTTPS://img.example.test/a.jpg",
            "https://img.example.test:8443/a.jpg?x=1#f"})
    void httpsCoverUrlsAreAccepted(String url) {
        assertThat(BookInputs.isAcceptableCoverUrl(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://img.example.test/a.jpg", "javascript:alert(1)", "data:image/png;base64,AAAA",
            "file:///etc/passwd", "ftp://x.test/a", "//img.example.test/a.jpg", "https:///nohost",
            "https://user:pw@img.example.test/a.jpg", "https://user@img.example.test/a.jpg",
            "https://img.example.test/a b.jpg", "https://img.example.test/a\nb", "https://img.example.test/\u0000",
            "not a url", "/relative/path", "https://"})
    void everythingElseIsRejected(String url) {
        assertThat(BookInputs.isAcceptableCoverUrl(url)).isFalse();
    }

    @Test
    void validInputIsNormalisedAndProducesNoProblems() {
        List<FieldProblem> problems = new ArrayList<>();

        BookValues values = BookInputs.validate(
                fields("0-306-40615-2", "  A Title  ", "  ", " Pub ", "  "), problems);

        assertThat(problems).isEmpty();
        assertThat(values.isbn()).isEqualTo("9780306406157");
        assertThat(values.title()).isEqualTo("A Title");
        assertThat(values.description()).isNull();
        assertThat(values.publisher()).isEqualTo("Pub");
        assertThat(values.coverUrl()).isNull();
    }

    @Test
    void everyBadFieldIsReported() {
        List<FieldProblem> problems = new ArrayList<>();

        BookInputs.validate(fields("123", "T\u0000", "d\u0007", "p\u001b", "http://x.test/a"), problems);

        assertThat(problems).extracting(FieldProblem::field)
                .containsExactlyInAnyOrder("isbn", "title", "description", "publisher", "coverUrl");
    }

    @Test
    void descriptionsMayContainLineBreaksAndTabsButTitlesMayNot() {
        List<FieldProblem> problems = new ArrayList<>();

        BookInputs.validate(fields("9780306406157", "Line1\nLine2", "Para1\r\n\tPara2", null, null), problems);

        assertThat(problems).extracting(FieldProblem::field).containsExactly("title");
    }

    @Test
    void problemMessagesNeverEchoTheSubmittedValue() {
        List<FieldProblem> problems = new ArrayList<>();

        BookInputs.validate(fields("SECRET-ISBN-VALUE", "T", null, null, "http://secret.example.test/x"), problems);

        assertThat(problems).extracting(FieldProblem::message).noneMatch(m -> m.contains("SECRET")
                || m.contains("secret"));
    }
}
