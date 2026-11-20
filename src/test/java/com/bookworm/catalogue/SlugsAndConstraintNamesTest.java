package com.bookworm.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SlugsAndConstraintNamesTest {

    // ---- Slugs ---------------------------------------------------------------------------------

    @Test
    void slugsAreLowerCaseAsciiSeparatedByHyphens() {
        assertThat(Slugs.slugify("Science Fiction")).isEqualTo("science-fiction");
        assertThat(Slugs.slugify("Non-Fiction")).isEqualTo("non-fiction");
        assertThat(Slugs.slugify("  Crime & Thrillers!  ")).isEqualTo("crime-thrillers");
        assertThat(Slugs.slugify("C++ / C#")).isEqualTo("c-c");
    }

    @Test
    void accentsAreStrippedAndCompatibilityFormsFolded() {
        assertThat(Slugs.slugify("Café Société")).isEqualTo("cafe-societe");
        assertThat(Slugs.slugify("Brontë")).isEqualTo("bronte");
    }

    @Test
    void aNameWithNoLatinLettersOrDigitsGivesAnEmptySlug() {
        assertThat(Slugs.slugify("अमिताव")).isEmpty();
        assertThat(Slugs.slugify("村上春樹")).isEmpty();
        assertThat(Slugs.slugify("...")).isEmpty();
    }

    @Test
    void everyGeneratedSlugMatchesTheDatabaseCheck() {
        for (String name : new String[] {"Science Fiction", "Café Société", "A  B   C", "x--y", "99 Problems",
                "-lead", "trail-", "a".repeat(150) + " b"}) {
            String slug = Slugs.slugify(name);
            assertThat(slug).matches("^[a-z0-9]+(-[a-z0-9]+)*$").hasSizeLessThanOrEqualTo(Slugs.MAX_LENGTH);
        }
    }

    @Test
    void collisionCandidatesAddTheLowestFreeNumericSuffix() {
        assertThat(Slugs.candidate("fantasy", 1)).isEqualTo("fantasy");
        assertThat(Slugs.candidate("fantasy", 2)).isEqualTo("fantasy-2");
        assertThat(Slugs.candidate("fantasy", 3)).isEqualTo("fantasy-3");
        assertThat(Slugs.candidate("fantasy", 12)).isEqualTo("fantasy-12");
    }

    @Test
    void aSuffixedSlugStillFitsAndStaysValidWhenTheBaseIsAtTheLimit() {
        String base = Slugs.slugify("a".repeat(Slugs.MAX_LENGTH));
        assertThat(base).hasSize(Slugs.MAX_LENGTH);

        String candidate = Slugs.candidate(base, 10);

        assertThat(candidate).hasSizeLessThanOrEqualTo(Slugs.MAX_LENGTH).endsWith("-10")
                .matches("^[a-z0-9]+(-[a-z0-9]+)*$");
    }

    @Test
    void truncationNeverLeavesATrailingHyphen() {
        String name = "a".repeat(98) + " b";
        String slug = Slugs.slugify(name);
        assertThat(slug).doesNotEndWith("-").hasSizeLessThanOrEqualTo(Slugs.MAX_LENGTH);
        assertThat(Slugs.candidate(slug, 2)).doesNotContain("--").matches("^[a-z0-9]+(-[a-z0-9]+)*$");
    }

    // ---- Constraint names ---------------------------------------------------------------------

    @Test
    void theNameComesFromHibernatesConstraintViolationException() {
        var cause = new ConstraintViolationException("dup", new SQLException("x"), "insert", "uk_books_isbn");
        var wrapped = new DataIntegrityViolationException("could not execute statement", cause);

        assertThat(ConstraintNames.of(wrapped)).contains("uk_books_isbn");
        assertThat(ConstraintNames.violated(wrapped, ConstraintNames.BOOK_ISBN)).isTrue();
        assertThat(ConstraintNames.violated(wrapped, ConstraintNames.CATEGORY_SLUG)).isFalse();
    }

    @Test
    void theNameFallsBackToThePostgresMessageAnywhereInTheCauseChain() {
        var root = new SQLException("ERROR: duplicate key value violates unique constraint \"ux_authors_name_ci\"\n"
                + "  Detail: Key (lower(name::text))=(george orwell) already exists.");
        var wrapped = new DataIntegrityViolationException("outer", new RuntimeException("middle", root));

        assertThat(ConstraintNames.of(wrapped)).contains("ux_authors_name_ci");
        assertThat(ConstraintNames.violated(wrapped, ConstraintNames.AUTHOR_NAME)).isTrue();
    }

    @Test
    void namesAreMatchedCaseInsensitively() {
        var cause = new ConstraintViolationException("dup", new SQLException("x"), "insert", "UK_CATEGORIES_SLUG");
        assertThat(ConstraintNames.violated(new DataIntegrityViolationException("x", cause),
                ConstraintNames.CATEGORY_SLUG)).isTrue();
    }

    @Test
    void anUnknownOrMissingConstraintMatchesNothing() {
        assertThat(ConstraintNames.of(new DataIntegrityViolationException("no constraint info"))).isEmpty();
        assertThat(ConstraintNames.violated(new DataIntegrityViolationException(
                "violates check constraint \"ck_books_price_positive\""), ConstraintNames.BOOK_ISBN)).isFalse();
        assertThat(ConstraintNames.violated(new RuntimeException(), ConstraintNames.BOOK_ISBN)).isFalse();
    }

    @Test
    void aSelfReferencingCauseChainDoesNotLoopForever() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(ConstraintNames.of(a)).isEmpty();
    }
}
