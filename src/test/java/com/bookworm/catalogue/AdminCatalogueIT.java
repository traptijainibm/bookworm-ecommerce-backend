package com.bookworm.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** End-to-end admin catalogue behaviour through the real security chain, JSON mapper, services and PostgreSQL. */
class AdminCatalogueIT extends AbstractAdminIT {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    // ---- security through the full stack ------------------------------------------------------------

    @Test
    void anonymousAndCustomerCallersAreRejectedAndAdminIsAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/admin/books")).andExpect(status().isUnauthorized());
        mockMvc.perform(asCustomer(get("/api/v1/admin/books"))).andExpect(status().isForbidden());
        mockMvc.perform(asCustomer(json(post("/api/v1/admin/authors"), "{\"name\":\"Itadm Nope\"}")))
                .andExpect(status().isForbidden());
        mockMvc.perform(adminGet("/api/v1/admin/books")).andExpect(status().isOk());

        Integer created = jdbc.queryForObject("select count(*) from authors where name like 'Itadm%'", Integer.class);
        assertThat(created).isZero();
    }

    @Test
    void aCustomerTokenCarryingAnAdminRoleClaimIsStillForbiddenBecauseTheDatabaseRoleWins() throws Exception {
        String forged = token(customerId, "ADMIN");

        mockMvc.perform(get("/api/v1/admin/books").header("Authorization", "Bearer " + forged))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminDemotedInTheDatabaseLosesAccessOnTheNextRequest() throws Exception {
        mockMvc.perform(adminGet("/api/v1/admin/books")).andExpect(status().isOk());

        jdbc.update("update users set role = 'CUSTOMER' where id = ?", adminId);

        mockMvc.perform(adminGet("/api/v1/admin/books")).andExpect(status().isForbidden());
    }

    @Test
    void aDisabledOrDeletedAdminIsRejectedOnTheNextRequest() throws Exception {
        jdbc.update("update users set enabled = false where id = ?", adminId);
        mockMvc.perform(adminGet("/api/v1/admin/books")).andExpect(status().isUnauthorized());

        jdbc.update("update users set enabled = true where id = ?", adminId);
        mockMvc.perform(adminGet("/api/v1/admin/books")).andExpect(status().isOk());

        jdbc.update("delete from users where id = ?", adminId);
        mockMvc.perform(adminGet("/api/v1/admin/books")).andExpect(status().isUnauthorized());
    }

    @Test
    void thereIsNoDeleteAndNoStockRouteEvenForAnAdmin() throws Exception {
        long category = createCategory("Itadm Routes");
        long author = createAuthor("Itadm Routes Author");
        long book = createBook(isbn(1), "Itadm Routes Book", category, author);

        mockMvc.perform(asAdmin(delete("/api/v1/admin/books/" + book))).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(asAdmin(delete("/api/v1/admin/categories/" + category)))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(asAdmin(delete("/api/v1/admin/authors/" + author))).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(asAdmin(json(patch("/api/v1/admin/books/" + book + "/stock"), "{\"stockQuantity\":9}")))
                .andExpect(status().isNotFound());
    }

    // ---- create, read, activate -----------------------------------------------------------------------

    @Test
    void aBookIsCreatedInactiveWithZeroStockUnderTheCanonicalIsbn13() throws Exception {
        long category = createCategory("Itadm Fiction");
        long a1 = createAuthor("Itadm Zeta");
        long a2 = createAuthor("Itadm Alpha");
        String isbn13 = isbn(2);

        MvcResult created = mockMvc.perform(asAdmin(json(post("/api/v1/admin/books"),
                        bookJson(isbn10(isbn13), "Itadm Created", category, a1, a2))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/admin/books/")))
                .andExpect(jsonPath("$.isbn").value(isbn13))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.stockQuantity").value(0))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.category.id").value((int) category))
                .andExpect(jsonPath("$.authors[0].name").value("Itadm Alpha"))
                .andExpect(jsonPath("$.authors[1].name").value("Itadm Zeta"))
                .andExpect(jsonPath("$.authors", hasSize(2)))
                .andReturn();

        long id = readLong(created, "$.id");
        assertThat(created.getResponse().getHeader("Location")).endsWith("/" + id);
        assertThat(jdbc.queryForObject("select stock_quantity from books where id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("select isbn from books where id = ?", String.class, id)).isEqualTo(isbn13);
        mockMvc.perform(adminGet("/api/v1/admin/books/" + id)).andExpect(status().isOk());
    }

    @Test
    void activatingAndDeactivatingControlsPublicVisibilityWhileAdminAlwaysSeesTheBook() throws Exception {
        long category = createCategory("Itadm Visibility");
        long author = createAuthor("Itadm Visible Author");
        long first = createBook(isbn(3), "Itadm Visible One", category, author);
        long second = createBook(isbn(4), "Itadm Visible Two", category, author);

        mockMvc.perform(get("/api/v1/books/" + first)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/books").param("q", "Itadm Visible"))
                .andExpect(jsonPath("$.totalElements").value(0));

        setActive(first, true);
        setActive(second, true);

        mockMvc.perform(get("/api/v1/books/" + first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stockQuantity").doesNotExist())
                .andExpect(jsonPath("$.inStock").value(false));
        mockMvc.perform(get("/api/v1/books").param("q", "Itadm Visible"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/books/" + first + "/related"))
                .andExpect(jsonPath("$[*].id", hasItem((int) second)));

        setActive(first, false);

        mockMvc.perform(get("/api/v1/books/" + first)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/books/" + second + "/related"))
                .andExpect(jsonPath("$[*].id", not(hasItem((int) first))));
        mockMvc.perform(adminGet("/api/v1/admin/books/" + first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void settingTheActiveFlagIsIdempotentAndOnlyAChangeBumpsTheVersion() throws Exception {
        long book = createBook(isbn(5), "Itadm Idempotent", createCategory("Itadm Idem"),
                createAuthor("Itadm Idem Author"));

        mockMvc.perform(asAdmin(json(patch("/api/v1/admin/books/" + book + "/active"), "{\"active\":false}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        mockMvc.perform(asAdmin(json(patch("/api/v1/admin/books/" + book + "/active"), "{\"active\":true}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(asAdmin(json(patch("/api/v1/admin/books/" + book + "/active"), "{\"active\":true}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void activatingAMissingBookIsNotFound() throws Exception {
        mockMvc.perform(asAdmin(json(patch("/api/v1/admin/books/999999999/active"), "{\"active\":true}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- update, versioning and stock protection ------------------------------------------------------

    @Test
    void updateReplacesDetailsAndNeverChangesStockOrTheActiveFlag() throws Exception {
        long category = createCategory("Itadm Update Cat");
        long other = createCategory("Itadm Update Other");
        long a1 = createAuthor("Itadm Upd One");
        long a2 = createAuthor("Itadm Upd Two");
        long book = createBook(isbn(6), "Itadm Before", category, a1);
        setActive(book, true);
        jdbc.update("update books set stock_quantity = 42 where id = ?", book);
        MvcResult before = mockMvc.perform(adminGet("/api/v1/admin/books/" + book)).andReturn();
        long version = readLong(before, "$.version");

        mockMvc.perform(adminPut("/api/v1/admin/books/" + book,
                        bookUpdateJson(version, isbn(7), "Itadm After", other, a2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Itadm After"))
                .andExpect(jsonPath("$.isbn").value(isbn(7)))
                .andExpect(jsonPath("$.category.id").value((int) other))
                .andExpect(jsonPath("$.authors", hasSize(1)))
                .andExpect(jsonPath("$.authors[0].id").value((int) a2))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.stockQuantity").value(42))
                .andExpect(jsonPath("$.version").value((int) version + 1));

        assertThat(jdbc.queryForObject("select stock_quantity from books where id = ?", Integer.class, book))
                .isEqualTo(42);
        assertThat(jdbc.queryForList("select author_id from book_authors where book_id = ?", Long.class, book))
                .containsExactly(a2);
    }

    @Test
    void aStaleVersionIsAConflictAndChangesNothing() throws Exception {
        long category = createCategory("Itadm Stale Cat");
        long author = createAuthor("Itadm Stale Author");
        long book = createBook(isbn(8), "Itadm Stale", category, author);

        mockMvc.perform(adminPut("/api/v1/admin/books/" + book,
                        bookUpdateJson(0, isbn(8), "Itadm Stale Winner", category, author)))
                .andExpect(status().isOk());

        mockMvc.perform(adminPut("/api/v1/admin/books/" + book,
                        bookUpdateJson(0, isbn(8), "Itadm Stale Loser", category, author)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));

        assertThat(jdbc.queryForObject("select title from books where id = ?", String.class, book))
                .isEqualTo("Itadm Stale Winner");
    }

    @Test
    void theActivePatchLeavesStockUntouched() throws Exception {
        long book = createBook(isbn(9), "Itadm Patch Stock", createCategory("Itadm Patch Cat"),
                createAuthor("Itadm Patch Author"));
        jdbc.update("update books set stock_quantity = 7 where id = ?", book);

        setActive(book, true);
        setActive(book, false);

        assertThat(jdbc.queryForObject("select stock_quantity from books where id = ?", Integer.class, book))
                .isEqualTo(7);
    }

    @Test
    void stockAndActiveFieldsInACreateBodyAreRejectedNotIgnored() throws Exception {
        long category = createCategory("Itadm Strict Cat");
        long author = createAuthor("Itadm Strict Author");
        String body = bookJson(isbn(10), "Itadm Strict", category, author);

        for (String extra : List.of("\"stockQuantity\":500", "\"active\":true", "\"stock\":1")) {
            mockMvc.perform(asAdmin(json(post("/api/v1/admin/books"), body.replace("{", "{" + extra + ","))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.detail", not(containsString("stock"))));
        }
        assertThat(jdbc.queryForObject("select count(*) from books where isbn = ?", Integer.class, isbn(10)))
                .isZero();
    }

    // ---- categories ----------------------------------------------------------------------------------

    @Test
    void renamingACategoryKeepsItsSlugAndItsBooks() throws Exception {
        MvcResult created = createCategoryResult("Itadm Slug Keeper");
        long category = readLong(created, "$.id");
        String slug = read(created, "$.slug");
        assertThat(slug).isEqualTo("itadm-slug-keeper");
        long book = createBook(isbn(11), "Itadm Slugged Book", category, createAuthor("Itadm Slug Author"));
        setActive(book, true);

        mockMvc.perform(adminPut("/api/v1/admin/categories/" + category, "{\"name\":\"Itadm Renamed Entirely\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Itadm Renamed Entirely"))
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.bookCount").value(1));

        mockMvc.perform(get("/api/v1/books").param("category", slug))
                .andExpect(jsonPath("$.content[*].id", hasItem((int) book)));
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(jsonPath("$[?(@.slug=='" + slug + "')].name", hasItem("Itadm Renamed Entirely")));
    }

    @Test
    void differentNamesWithTheSameSlugGetNumericSuffixes() throws Exception {
        MvcResult first = createCategoryResult("Itadm Sci-Fi");
        MvcResult second = createCategoryResult("Itadm Sci Fi");
        MvcResult third = createCategoryResult("Itadm Sci.Fi");
        MvcResult fourth = createCategoryResult("Itadm Sci,Fi");

        assertThat(List.of(first, second, third, fourth)).allSatisfy(
                r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
        assertThat(read(first, "$.slug")).isEqualTo("itadm-sci-fi");
        assertThat(read(second, "$.slug")).isEqualTo("itadm-sci-fi-2");
        assertThat(read(third, "$.slug")).isEqualTo("itadm-sci-fi-3");
        assertThat(read(fourth, "$.slug")).isEqualTo("itadm-sci-fi-4");
    }

    @Test
    void aCategoryNameWithNoLatinLettersOrDigitsIsRejected() throws Exception {
        mockMvc.perform(asAdmin(json(post("/api/v1/admin/categories"), "{\"name\":\"\u0915\u0935\u093f\u0924\u093e\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void duplicateNamesAreConflictsRegardlessOfCaseOrSpacing() throws Exception {
        createCategory("Itadm Dup Cat");
        createAuthor("Itadm Dup Author");

        mockMvc.perform(asAdmin(json(post("/api/v1/admin/categories"), "{\"name\":\"ITADM dup CAT\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
        mockMvc.perform(asAdmin(json(post("/api/v1/admin/authors"), "{\"name\":\"itadm DUP author\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
        mockMvc.perform(asAdmin(json(post("/api/v1/admin/authors"), "{\"name\":\"  Itadm   Dup  Author \"}")))
                .andExpect(status().isConflict());
    }

    @Test
    void renamingToAnotherCategorysNameIsAConflictButChangingOnlyTheCaseOfItsOwnNameIsAllowed() throws Exception {
        long keep = createCategory("Itadm Keep Name");
        createCategory("Itadm Taken Name");

        mockMvc.perform(adminPut("/api/v1/admin/categories/" + keep, "{\"name\":\"itadm taken name\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(adminPut("/api/v1/admin/categories/" + keep, "{\"name\":\"ITADM KEEP NAME\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void categoryAndAuthorBookCountsIncludeInactiveBooks() throws Exception {
        long category = createCategory("Itadm Counted");
        long author = createAuthor("Itadm Counted Author");
        long active = createBook(isbn(12), "Itadm Counted One", category, author);
        createBook(isbn(13), "Itadm Counted Two", category, author);
        setActive(active, true);

        mockMvc.perform(adminGet("/api/v1/admin/categories/" + category)).andExpect(jsonPath("$.bookCount").value(2));
        mockMvc.perform(adminGet("/api/v1/admin/authors/" + author)).andExpect(jsonPath("$.bookCount").value(2));
        mockMvc.perform(adminGet("/api/v1/admin/categories"))
                .andExpect(jsonPath("$[?(@.id==" + category + ")].bookCount", hasItem(2)));
    }

    // ---- duplicates and validation ----------------------------------------------------------------------

    @Test
    void anIsbn10AndTheMatchingIsbn13AreTheSameBook() throws Exception {
        long category = createCategory("Itadm Isbn Cat");
        long author = createAuthor("Itadm Isbn Author");
        createBook(isbn(14), "Itadm Isbn First", category, author);

        mockMvc.perform(asAdmin(json(post("/api/v1/admin/books"),
                        bookJson(isbn10(isbn(14)), "Itadm Isbn Second", category, author))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
        String hyphenated = isbn(14).substring(0, 3) + "-" + isbn(14).substring(3);
        mockMvc.perform(asAdmin(json(post("/api/v1/admin/books"),
                        bookJson(hyphenated, "Itadm Isbn Third", category, author))))
                .andExpect(status().isConflict());
    }

    @Test
    void updatingToAnIsbnOwnedByAnotherBookIsAConflict() throws Exception {
        long category = createCategory("Itadm Swap Cat");
        long author = createAuthor("Itadm Swap Author");
        createBook(isbn(15), "Itadm Swap A", category, author);
        long b = createBook(isbn(16), "Itadm Swap B", category, author);

        mockMvc.perform(adminPut("/api/v1/admin/books/" + b,
                        bookUpdateJson(0, isbn(15), "Itadm Swap B", category, author)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void invalidReferencesAndIsbnsAreReportedAsFieldErrors() throws Exception {
        long category = createCategory("Itadm Ref Cat");
        long author = createAuthor("Itadm Ref Author");

        mockMvc.perform(asAdmin(json(post("/api/v1/admin/books"),
                        bookJson("9780306406158", "Itadm Bad", 999999999L, author, 999999998L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("isbn", "categoryId", "authorIds")));
        mockMvc.perform(asAdmin(json(post("/api/v1/admin/books"),
                        bookJson(isbn(17), "Itadm Dup Authors", category, author, author))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("authorIds"));
        assertThat(jdbc.queryForObject("select count(*) from books where isbn like '978999%'", Integer.class))
                .isZero();
    }

    @Test
    void coverUrlMustBeHttpsWithoutCredentials() throws Exception {
        long category = createCategory("Itadm Cover Cat");
        long author = createAuthor("Itadm Cover Author");
        String base = bookJson(isbn(18), "Itadm Cover", category, author);

        for (String bad : List.of("http://img.example.test/a.jpg", "javascript:alert(1)",
                "https://user:pw@img.example.test/a.jpg")) {
            mockMvc.perform(asAdmin(json(post("/api/v1/admin/books"),
                            base.replace("}", ",\"coverUrl\":\"" + bad + "\"}"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("coverUrl"));
        }
        mockMvc.perform(asAdmin(json(post("/api/v1/admin/books"),
                        base.replace("}", ",\"coverUrl\":\"https://img.example.test/a.jpg\"}"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.coverUrl").value("https://img.example.test/a.jpg"));
    }

    @Test
    void unknownFieldsAreRejectedOnEveryAdminWriteWithoutEchoingTheirNames() throws Exception {
        long category = createCategory("Itadm Unknown Cat");
        long author = createAuthor("Itadm Unknown Author");
        long book = createBook(isbn(19), "Itadm Unknown", category, author);

        List<MvcResult> results = List.of(
                mockMvc.perform(asAdmin(json(post("/api/v1/admin/categories"),
                        "{\"name\":\"Itadm Unk1\",\"zzSlug\":\"x\"}"))).andReturn(),
                mockMvc.perform(asAdmin(json(post("/api/v1/admin/authors"),
                        "{\"name\":\"Itadm Unk2\",\"zzRole\":\"ADMIN\"}"))).andReturn(),
                mockMvc.perform(adminPut("/api/v1/admin/categories/" + category,
                        "{\"name\":\"Itadm Unk3\",\"zzId\":5}")).andReturn(),
                mockMvc.perform(adminPut("/api/v1/admin/authors/" + author,
                        "{\"name\":\"Itadm Unk4\",\"zzX\":5}")).andReturn(),
                mockMvc.perform(asAdmin(json(patch("/api/v1/admin/books/" + book + "/active"),
                        "{\"active\":true,\"zzStock\":5}"))).andReturn(),
                mockMvc.perform(adminPut("/api/v1/admin/books/" + book,
                        bookUpdateJson(0, isbn(19), "Itadm Unk5", category, author).replace("}",
                                ",\"zzStock\":9}"))).andReturn());
        for (MvcResult result : results) {
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).contains("MALFORMED_REQUEST").doesNotContain("zz");
        }
        assertThat(jdbc.queryForObject("select count(*) from authors where name ~ '^Itadm Unk[0-9]$'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select active from books where id = ?", Boolean.class, book)).isFalse();
    }

    // ---- listing ----------------------------------------------------------------------------------------

    @Test
    void bookListFiltersByTextIsbnCategoryAndActiveFlagAndPaginates() throws Exception {
        long catA = createCategory("Itadm List A");
        long catB = createCategory("Itadm List B");
        long writer = createAuthor("Itadm Listwriter");
        long b1 = createBook(isbn(20), "Itadm Listing Alpha", catA, writer);
        long b2 = createBook(isbn(21), "Itadm Listing Beta", catA, writer);
        long b3 = createBook(isbn(22), "Itadm Listing Gamma", catB, createAuthor("Itadm Other Writer"));
        setActive(b1, true);

        mockMvc.perform(adminGet("/api/v1/admin/books").param("q", "itadm listing"))
                .andExpect(jsonPath("$.totalElements").value(3));
        mockMvc.perform(adminGet("/api/v1/admin/books").param("q", "listwriter"))
                .andExpect(jsonPath("$.content[*].id", contains((int) b1, (int) b2)));
        mockMvc.perform(adminGet("/api/v1/admin/books").param("q", isbn(22)))
                .andExpect(jsonPath("$.content[*].id", contains((int) b3)));
        mockMvc.perform(adminGet("/api/v1/admin/books").param("q", isbn10(isbn(22))))
                .andExpect(jsonPath("$.content[*].id", contains((int) b3)));
        mockMvc.perform(adminGet("/api/v1/admin/books").param("q", "itadm listing").param("active", "true"))
                .andExpect(jsonPath("$.content[*].id", contains((int) b1)));
        mockMvc.perform(adminGet("/api/v1/admin/books").param("q", "itadm listing").param("active", "false"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(adminGet("/api/v1/admin/books").param("q", "itadm listing")
                        .param("categoryId", String.valueOf(catB)))
                .andExpect(jsonPath("$.content[*].id", contains((int) b3)));
        mockMvc.perform(adminGet("/api/v1/admin/books").param("q", "itadm listing").param("size", "2")
                        .param("page", "1").param("sort", "title,asc"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value((int) b3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].description").doesNotExist());
    }

    @Test
    void bookListTreatsWildcardsAndInjectionAsPlainText() throws Exception {
        long category = createCategory("Itadm Wild Cat");
        createBook(isbn(23), "Itadm 100% Literal", category, createAuthor("Itadm Wild Author"));
        createBook(isbn(24), "Itadm 100X Lookalike", category, createAuthor("Itadm Wild Author Two"));

        mockMvc.perform(adminGet("/api/v1/admin/books").param("q", "100%"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(adminGet("/api/v1/admin/books").param("q", "'; DROP TABLE books; --"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        assertThat(jdbc.queryForObject("select count(*) from books where isbn like '978999%'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void bookListSortAllowListIsEnforcedAndOrderIsStable() throws Exception {
        long category = createCategory("Itadm Sort Cat");
        long author = createAuthor("Itadm Sort Author");
        createBook(isbn(25), "Itadm Sort B", category, author);
        createBook(isbn(26), "Itadm Sort A", category, author);

        mockMvc.perform(adminGet("/api/v1/admin/books").param("q", "itadm sort").param("sort", "title,desc"))
                .andExpect(jsonPath("$.content[0].title").value("Itadm Sort B"));
        mockMvc.perform(adminGet("/api/v1/admin/books").param("q", "itadm sort").param("sort", "isbn"))
                .andExpect(jsonPath("$.content[0].isbn").value(isbn(25)));
        for (String bad : List.of("stockQuantity", "password_hash", "active", "title,up", "title;drop")) {
            mockMvc.perform(adminGet("/api/v1/admin/books").param("sort", bad)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void authorListSearchesSortsAndCounts() throws Exception {
        long category = createCategory("Itadm Authors Cat");
        long prolific = createAuthor("Itadm Prolific");
        createAuthor("Itadm Quiet");
        createBook(isbn(27), "Itadm Auth Book 1", category, prolific);
        createBook(isbn(28), "Itadm Auth Book 2", category, prolific);

        mockMvc.perform(adminGet("/api/v1/admin/authors").param("q", "itadm p"))
                .andExpect(jsonPath("$.content[0].name").value("Itadm Prolific"))
                .andExpect(jsonPath("$.content[0].bookCount").value(2));
        mockMvc.perform(adminGet("/api/v1/admin/authors").param("q", "itadm").param("sort", "name,desc"))
                .andExpect(jsonPath("$.content[0].name").value("Itadm Quiet"))
                .andExpect(jsonPath("$.content[0].bookCount").value(0));
        mockMvc.perform(adminGet("/api/v1/admin/authors").param("sort", "bookCount"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listingUsesAConstantNumberOfStatementsWhateverThePageContains() throws Exception {
        long category = createCategory("Itadm N1 Cat");
        long author = createAuthor("Itadm N1 Author");
        long author2 = createAuthor("Itadm N1 Author Two");
        for (int i = 0; i < 2; i++) {
            createBook(isbn(30 + i), "Itadm N1 Small " + i, category, author);
        }
        entityManagerFactory.unwrap(SessionFactory.class).getStatistics().setStatisticsEnabled(true);

        long small = statementsFor("/api/v1/admin/books", "itadm n1 small");
        for (int i = 0; i < 12; i++) {
            createBook(isbn(40 + i), "Itadm N1 Large " + i, createCategory("Itadm N1 Cat " + i), author, author2);
        }
        long large = statementsFor("/api/v1/admin/books", "itadm n1");
        long authorsSmall = statementsFor("/api/v1/admin/authors", "itadm n1 author two");
        long authorsLarge = statementsFor("/api/v1/admin/authors", "itadm n1");

        assertThat(large).as("book list statements for 14 vs 2 books").isEqualTo(small);
        assertThat(authorsLarge).as("author list statements").isEqualTo(authorsSmall);
    }

    private long statementsFor(String path, String q) throws Exception {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long before = statistics.getPrepareStatementCount();
        mockMvc.perform(adminGet(path).param("q", q).param("size", "50")).andExpect(status().isOk());
        return statistics.getPrepareStatementCount() - before;
    }

    // ---- public catalogue integrity ---------------------------------------------------------------------

    @Test
    void publicReadsNeverModifyABook() throws Exception {
        long category = createCategory("Itadm Readonly Cat");
        long author = createAuthor("Itadm Readonly Author");
        long book = createBook(isbn(60), "Itadm Readonly", category, author);
        setActive(book, true);
        Map<String, Object> before = jdbc.queryForMap(
                "select version, updated_at, stock_quantity from books where id = ?", book);

        mockMvc.perform(get("/api/v1/books/" + book)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/books").param("q", "itadm readonly")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/books/" + book + "/related")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/categories")).andExpect(status().isOk());

        Map<String, Object> after = jdbc.queryForMap(
                "select version, updated_at, stock_quantity from books where id = ?", book);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void publicPricesStayExactAfterAnAdminUpdate() throws Exception {
        long category = createCategory("Itadm Price Cat");
        long author = createAuthor("Itadm Price Author");
        long book = createBook(isbn(61), "Itadm Priced", category, author);
        setActive(book, true);

        mockMvc.perform(adminPut("/api/v1/admin/books/" + book,
                        bookUpdateJson(1, isbn(61), "Itadm Priced", category, author).replace("19.99", "1234.50")))
                .andExpect(status().isOk());

        MvcResult publicBook = mockMvc.perform(get("/api/v1/books/" + book)).andReturn();
        assertThat(new BigDecimal(read(publicBook, "$.price"))).isEqualByComparingTo("1234.50");
    }

    // ---- API surface ------------------------------------------------------------------------------------

    @Test
    void theOpenApiDocumentDescribesTheAdminApiWithBearerAuthAndNoStockOrDelete() throws Exception {
        MvcResult docs = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        String body = docs.getResponse().getContentAsString();

        for (String path : List.of("/api/v1/admin/books", "/api/v1/admin/books/{id}",
                "/api/v1/admin/books/{id}/active", "/api/v1/admin/categories", "/api/v1/admin/categories/{id}",
                "/api/v1/admin/authors", "/api/v1/admin/authors/{id}")) {
            mockMvc.perform(get("/v3/api-docs")).andExpect(jsonPath("$.paths['" + path + "']").exists());
        }
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/admin/books'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/books/{id}/active'].patch.security[0].bearerAuth")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/books/{id}'].delete").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.BookCreateRequest.properties.stockQuantity")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.BookUpdateRequest.properties.stockQuantity")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.BookCreateRequest.properties.active").doesNotExist());
        assertThat(body).doesNotContain("/stock");
    }
}
