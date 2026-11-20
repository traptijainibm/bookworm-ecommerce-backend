package com.bookworm.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import java.text.Normalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogueNamesTest {

    private static final int MAX = CatalogueNames.MAX_AUTHOR;

    @ParameterizedTest
    @ValueSource(strings = {"George Orwell", "J.R.R. Tolkien", "Ursula K. Le Guin", "Non-Fiction", "O'Brien",
            "Brontë", "Rowling, J.K.", "Science & Fiction (Classic)", "Tolstoy’s World", "Book 2: The Sequel!",
            "C++ in Depth", "अमिताव घोष", "村上春樹", "Gabriel García Márquez"})
    void ordinaryNamesAreAccepted(String name) {
        assertThat(CatalogueNames.violation(name, MAX)).isNull();
    }

    @Test
    void namesAreTrimmedAndWhitespaceIsCollapsed() {
        assertThat(CatalogueNames.require("name", "  George \u00A0  Orwell\u2003 ", MAX)).isEqualTo("George Orwell");
    }

    @Test
    void namesAreStoredInNfcForm() {
        String decomposed = Normalizer.normalize("Brontë", Normalizer.Form.NFD);
        assertThat(decomposed).isNotEqualTo("Brontë");

        assertThat(CatalogueNames.require("name", decomposed, MAX)).isEqualTo("Brontë");
    }

    @Test
    void anNfdAndAnNfcSpellingBecomeTheSameStoredName() {
        String nfc = Normalizer.normalize("García", Normalizer.Form.NFC);
        String nfd = Normalizer.normalize("García", Normalizer.Form.NFD);

        assertThat(CatalogueNames.normalise(nfd)).isEqualTo(CatalogueNames.normalise(nfc));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Zero\u200BWidth",       // zero-width space (format)
            "Joiner\u200DName",      // zero-width joiner (format)
            "Bidi\u202EName",        // right-to-left override (format)
            "Mark\uFEFFName",        // byte order mark (format)
            "Soft\u00ADHyphen",      // soft hyphen (format)
            "Line\nBreak",           // control
            "Tab\tName",             // control
            "Null\u0000Name",        // control
            "Private\uE000Use",      // private use
            "Unassigned\u0378Name",  // unassigned code point
            "Lone\uD800Surrogate"})  // unpaired surrogate
    void formatControlPrivateUseUnassignedAndSurrogateCharactersAreRejected(String name) {
        assertThat(CatalogueNames.violation(name, MAX)).isEqualTo("contains characters that are not allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Ｆｕｌｌｗｉｄｔｈ",  // full-width letters
            "ﬁne",                 // fi ligature
            "Room ①",              // circled digit
            "x²",                  // superscript two
            "™ Brand"})            // trade mark sign
    void compatibilityCharactersAreRejected(String name) {
        assertThat(CatalogueNames.violation(name, MAX)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bad<script>", "name; DROP TABLE", "50% off", "under_score", "back\\slash",
            "quote\"mark", "at@sign", "hash#tag", "pipe|bar", "emoji 😀", "dollar$", "equals=sign", "{braces}",
            "tilde~"})
    void charactersOutsideTheAllowlistAreRejected(String name) {
        assertThat(CatalogueNames.violation(name, MAX)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\u00A0\u2003", "...", "- - -", "()", "&"})
    void blankOrPunctuationOnlyNamesAreRejected(String name) {
        assertThat(CatalogueNames.violation(name, MAX)).isNotNull();
    }

    @Test
    void nullIsRejected() {
        assertThat(CatalogueNames.violation(null, MAX)).isEqualTo("must not be blank");
    }

    @Test
    void lengthIsCountedInCodePointsNotUtf16Units() {
        assertThat(CatalogueNames.violation("a".repeat(100), CatalogueNames.MAX_CATEGORY)).isNull();
        assertThat(CatalogueNames.violation("a".repeat(101), CatalogueNames.MAX_CATEGORY))
                .isEqualTo("must be at most 100 characters");
        // 100 supplementary-plane letters (2 UTF-16 units each) are 100 code points, so they fit.
        String supplementary = "\uD835\uDC9C".repeat(1); // MATHEMATICAL SCRIPT CAPITAL A (compatibility char)
        assertThat(CatalogueNames.violation(supplementary, 100)).isNotNull(); // rejected for being a compat char
        String cjkExtB = "\uD840\uDC0B".repeat(100);   // CJK Extension B ideograph, 2 UTF-16 units
        assertThat(CatalogueNames.violation(cjkExtB, 100)).isNull();
        assertThat(CatalogueNames.violation(cjkExtB + "\uD840\uDC0B", 100)).isEqualTo("must be at most 100 characters");
    }

    @Test
    void aHugeInputIsRejectedWithoutBeingNormalised() {
        assertThat(CatalogueNames.violation("a".repeat(1_000_000), MAX)).isEqualTo("must be at most 200 characters");
    }

    @Test
    void requireThrowsAFieldErrorWithAFixedMessage() {
        assertThatThrownBy(() -> CatalogueNames.require("name", "Bad<name>", MAX))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getFieldProblems()).hasSize(1);
                    assertThat(ex.getFieldProblems().get(0).field()).isEqualTo("name");
                    assertThat(ex.getFieldProblems().get(0).message()).doesNotContain("Bad<name>");
                });
    }
}
