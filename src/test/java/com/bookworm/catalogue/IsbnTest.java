package com.bookworm.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IsbnTest {

    @Test
    void aValidIsbn13IsKeptAsIs() {
        assertThat(Isbn.canonicalise("9780306406157")).contains("9780306406157");
        assertThat(Isbn.canonicalise("9789990000016")).contains("9789990000016");
    }

    @Test
    void spacesAndHyphensAreStripped() {
        assertThat(Isbn.canonicalise("978-0-306-40615-7")).contains("9780306406157");
        assertThat(Isbn.canonicalise(" 978 0306 40615 7 ")).contains("9780306406157");
    }

    @Test
    void aValidIsbn10IsConvertedToIsbn13() {
        assertThat(Isbn.canonicalise("0306406152")).contains("9780306406157");
        assertThat(Isbn.canonicalise("0-306-40615-2")).contains("9780306406157");
    }

    @Test
    void anIsbn10EndingInXIsAcceptedInEitherCaseAndConverted() {
        assertThat(Isbn.canonicalise("080442957X")).contains("9780804429573");
        assertThat(Isbn.canonicalise("080442957x")).contains("9780804429573");
        assertThat(Isbn.canonicalise("0-8044-2957-x")).contains("9780804429573");
    }

    @Test
    void anIsbn10AndItsIsbn13CanonicaliseToTheSameValue() {
        assertThat(Isbn.canonicalise("0306406152")).isEqualTo(Isbn.canonicalise("978-0-306-40615-7"));
    }

    @Test
    void a979PrefixIsAccepted() {
        // 979-10-90636-07-1 (a valid 979 ISBN-13)
        assertThat(Isbn.canonicalise("9791090636071")).contains("9791090636071");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "9780306406158",      // ISBN-13 wrong check digit
            "0306406153",         // ISBN-10 wrong check digit
            "9770306406150",      // prefix 977 is not a book ISBN
            "030640615X",         // X is only valid when it is the correct check value
            "97803064061",        // too short
            "97803064061577",     // too long
            "03064061",           // too short for ISBN-10
            "X306406152",         // X in the wrong position
            "03064X6152",         // X in the middle
            "978030640615X",      // X is not allowed in an ISBN-13
            "0306406152 ABC",     // trailing junk
            "٩٧٨٠٣٠٦٤٠٦١٥٧",      // Arabic-Indic digits are not ASCII digits
            "978\t0306406157",    // tab is not an allowed separator
            "978_0306406157",     // underscore is not an allowed separator
            "978–0306406157",     // en dash is not a hyphen
            "",
            "   ",
            "----"})
    void invalidIsbnsAreRejected(String raw) {
        assertThat(Isbn.canonicalise(raw)).isEqualTo(Optional.empty());
    }

    @Test
    void nullAndOverlongInputAreRejected() {
        assertThat(Isbn.canonicalise(null)).isEmpty();
        assertThat(Isbn.canonicalise("9".repeat(Isbn.MAX_RAW_LENGTH + 1))).isEmpty();
        assertThat(Isbn.canonicalise("-".repeat(1000) + "9780306406157")).isEmpty();
    }

    @Test
    void everyCanonicalIsbnMatchesTheDatabaseShape() {
        for (String raw : new String[] {"0306406152", "080442957X", "978-0-306-40615-7"}) {
            assertThat(Isbn.canonicalise(raw).orElseThrow()).matches("^97[89][0-9]{10}$");
        }
    }
}
