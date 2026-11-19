package com.bookworm.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LikePatternsTest {

    @Test
    void wrapsInputInWildcardsAndLowerCasesIt() {
        assertThat(LikePatterns.containsIgnoreCase("DuNe")).isEqualTo("%dune%");
    }

    @Test
    void escapesPercentSoItMatchesLiterally() {
        assertThat(LikePatterns.containsIgnoreCase("100%")).isEqualTo("%100\\%%");
    }

    @Test
    void escapesUnderscoreSoItMatchesLiterally() {
        assertThat(LikePatterns.containsIgnoreCase("a_b")).isEqualTo("%a\\_b%");
    }

    @Test
    void escapesTheEscapeCharacterItself() {
        assertThat(LikePatterns.containsIgnoreCase("a\\b")).isEqualTo("%a\\\\b%");
    }

    @Test
    void escapesEveryOccurrenceAndLeavesOtherCharactersAlone() {
        assertThat(LikePatterns.containsIgnoreCase("%_\\ x'\"")).isEqualTo("%\\%\\_\\\\ x'\"%");
    }
}
