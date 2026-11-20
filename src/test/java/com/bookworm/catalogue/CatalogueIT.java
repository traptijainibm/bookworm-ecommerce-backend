package com.bookworm.catalogue;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookworm.testsupport.TestJwtSecretInitializer;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end catalogue tests against the real PostgreSQL {@code bookworm_test} database (no H2).
 * Fixtures are inserted with JDBC inside the test transaction, which is rolled back after every test,
 * so nothing is left behind. The {@code test} profile does not load the dev seed data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestJwtSecretInitializer.class)
@Transactional
class CatalogueIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private long alpha;
    private long beta;
    private long authorZed;
    private long authorYan;
    private long alphaOne;
    private long alphaTwoSoldOut;
    private long betaThree;
    private long alphaFour;
    private long inactive;
    private long percentBook;
    private long lookalike;

    @BeforeEach
    void fixtures() {
        alpha = category("It Alpha", "it-alpha");
        beta = category("It Beta", "it-beta");
        authorZed = author("Zed Itauthor");
        authorYan = author("Yan Itauthor");

        alphaOne = book("9789990000011", "Zqx Alpha One", alpha, 5, true, authorZed);
        alphaTwoSoldOut = book("9789990000028", "Zqx Alpha Two", alpha, 0, true, authorZed);
        betaThree = book("9789990000035", "Zqx Beta Three", beta, 9, true, authorYan);
        alphaFour = book("9789990000042", "Zqx Alpha Four", alpha, 2, true, authorYan);
        inactive = book("9789990000059", "Zqx Withdrawn", alpha, 4, false, authorZed);
        percentBook = book("9789990000066", "100% Zqx_Literal", beta, 1, true, authorYan);
        lookalike = book("9789990000073", "100X ZqxXLiteral", beta, 1, true, authorYan);
    }

    // ---- search ----------------------------------------------------------------------------------

    @Test
    void searchMatchesTitleCaseInsensitivelyAndHidesInactiveBooks() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("q", "ZQX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem((int) alphaOne)))
                .andExpect(jsonPath("$.content[*].id", not(hasItem((int) inactive))));
    }

    @Test
    void searchMatchesAuthorName() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("q", "zed it"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains((int) alphaOne, (int) alphaTwoSoldOut)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void percentInQueryMatchesLiterallyNotAsAWildcard() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("q", "100%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains((int) percentBook)));
    }

    @Test
    void underscoreInQueryMatchesLiterallyNotAsASingleCharacterWildcard() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("q", "Zqx_Literal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains((int) percentBook)))
                .andExpect(jsonPath("$.content[*].id", not(hasItem((int) lookalike))));
    }

    @Test
    void sqlInjectionAttemptIsTreatedAsPlainTextAndTablesSurvive() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("q", "'; DROP TABLE books; --"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()));

        Integer books = jdbc.queryForObject("select count(*) from books", Integer.class);
        org.assertj.core.api.Assertions.assertThat(books).isGreaterThanOrEqualTo(7);
    }

    @Test
    void categoryFilterCombinesWithSearchAndSortsByTitle() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("q", "zqx").param("category", "it-alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title",
                        contains("Zqx Alpha Four", "Zqx Alpha One", "Zqx Alpha Two")))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void unknownCategoryReturnsAnEmptyPageNotAnError() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("category", "no-such-category"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void pagingReturnsStableSlicesAndTotals() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("category", "it-alpha").param("size", "2").param("page", "0"))
                .andExpect(jsonPath("$.content[*].title", contains("Zqx Alpha Four", "Zqx Alpha One")))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/v1/books").param("category", "it-alpha").param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.content[*].title", contains("Zqx Alpha Two")));
    }

    @Test
    void sortByPriceDescendingOrdersByPriceThenId() throws Exception {
        jdbc.update("update books set price = 500.00 where id = ?", alphaFour);
        jdbc.update("update books set price = 300.00 where id = ?", alphaOne);
        jdbc.update("update books set price = 300.00 where id = ?", alphaTwoSoldOut);

        mockMvc.perform(get("/api/v1/books").param("category", "it-alpha").param("sort", "price,desc"))
                .andExpect(jsonPath("$.content[*].id",
                        contains((int) alphaFour, (int) alphaOne, (int) alphaTwoSoldOut)));
    }

    @Test
    void searchResultsDoNotLeakStockCountOrInternalFields() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("q", "Zqx Alpha One"))
                .andExpect(jsonPath("$.content[0].inStock").value(true))
                .andExpect(jsonPath("$.content[0].stockQuantity").doesNotExist())
                .andExpect(jsonPath("$.content[0].active").doesNotExist())
                .andExpect(jsonPath("$.content[0].version").doesNotExist());
    }

    // ---- details ---------------------------------------------------------------------------------

    @Test
    void detailsIncludeAuthorsCategoryAndAvailability() throws Exception {
        mockMvc.perform(get("/api/v1/books/{id}", alphaOne))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Zqx Alpha One"))
                .andExpect(jsonPath("$.price").value(100.00))
                .andExpect(jsonPath("$.category.slug").value("it-alpha"))
                .andExpect(jsonPath("$.authors", hasSize(1)))
                .andExpect(jsonPath("$.authors[0].name").value("Zed Itauthor"))
                .andExpect(jsonPath("$.inStock").value(true))
                .andExpect(jsonPath("$.stockQuantity").doesNotExist());
    }

    @Test
    void soldOutBookIsStillVisibleButNotInStock() throws Exception {
        mockMvc.perform(get("/api/v1/books/{id}", alphaTwoSoldOut))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inStock").value(false));
    }

    @Test
    void inactiveAndUnknownBooksReturnNotFoundWithProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/books/{id}", inactive))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        mockMvc.perform(get("/api/v1/books/{id}", 999999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- related ---------------------------------------------------------------------------------

    @Test
    void relatedPutsSameAuthorFirstThenSameCategoryAndExcludesSelfAndInactive() throws Exception {
        // alphaOne: same author (Zed) -> alphaTwoSoldOut; same category (alpha) -> alphaFour.
        // inactive shares both author and category but must never appear; alphaOne must never relate to itself.
        mockMvc.perform(get("/api/v1/books/{id}/related", alphaOne))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains((int) alphaTwoSoldOut, (int) alphaFour)));
    }

    @Test
    void relatedRespectsTheLimitAndKeepsAuthorMatchesFirst() throws Exception {
        mockMvc.perform(get("/api/v1/books/{id}/related", alphaOne).param("limit", "1"))
                .andExpect(jsonPath("$[*].id", contains((int) alphaTwoSoldOut)));
    }

    @Test
    void relatedFallsBackToCategoryOnlyWhenNoAuthorMatchExists() throws Exception {
        long loner = book("9789990000080", "Zqx Lone Author Book", alpha, 3, true, author("Solo Itauthor"));

        mockMvc.perform(get("/api/v1/books/{id}/related", loner))
                .andExpect(jsonPath("$[*].id", contains((int) alphaFour, (int) alphaOne, (int) alphaTwoSoldOut)));
    }

    @Test
    void relatedForInactiveOrUnknownBookIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/books/{id}/related", inactive)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/books/{id}/related", 999999999L)).andExpect(status().isNotFound());
    }

    // ---- categories ------------------------------------------------------------------------------

    @Test
    void categoriesAreListedAlphabetically() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].slug", hasItem("it-alpha")))
                .andExpect(jsonPath("$[*].slug", hasItem("it-beta")));
        var body = mockMvc.perform(get("/api/v1/categories")).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body.indexOf("it-alpha")).isLessThan(body.indexOf("it-beta"));
    }

    // ---- API surface -----------------------------------------------------------------------------

    @Test
    void publicCatalogueEndpointsAreGetOnlyAndNoDeleteExistsAnywhere() throws Exception {
        // The PUBLIC catalogue is read-only. Phase 3 adds the admin catalogue writes (POST/PUT/PATCH) under
        // /api/v1/admin, but no operation anywhere is a DELETE and there is no stock endpoint.
        for (String path : java.util.List.of("/api/v1/books", "/api/v1/books/{id}", "/api/v1/books/{id}/related",
                "/api/v1/categories")) {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paths['" + path + "'].get").exists())
                    .andExpect(jsonPath("$.paths['" + path + "'].post").doesNotExist())
                    .andExpect(jsonPath("$.paths['" + path + "'].put").doesNotExist())
                    .andExpect(jsonPath("$.paths['" + path + "'].patch").doesNotExist())
                    .andExpect(jsonPath("$.paths['" + path + "'].delete").doesNotExist());
        }
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/admin/probe']").doesNotExist())
                .andExpect(jsonPath("$.paths", org.hamcrest.Matchers.aMapWithSize(15)))
                .andExpect(jsonPath("$.paths.*.delete").isEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/password'].post").exists());
    }

    // ---- JDBC fixtures (rolled back with the test transaction) ------------------------------------

    private long category(String name, String slug) {
        return jdbc.queryForObject("insert into categories (name, slug) values (?, ?) returning id",
                Long.class, name, slug);
    }

    private long author(String name) {
        return jdbc.queryForObject("insert into authors (name) values (?) returning id", Long.class, name);
    }

    private long book(String isbn, String title, long categoryId, int stock, boolean active, long authorId) {
        Long id = jdbc.queryForObject("""
                insert into books (isbn, title, description, price, category_id, stock_quantity, active)
                values (?, ?, 'IT fixture', ?, ?, ?, ?) returning id
                """, Long.class, isbn, title, new BigDecimal("100.00"), categoryId, stock, active);
        jdbc.update("insert into book_authors (book_id, author_id) values (?, ?)", id, authorId);
        return id;
    }
}
