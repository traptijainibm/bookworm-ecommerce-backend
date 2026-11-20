package com.bookworm.catalogue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookworm.common.error.GlobalExceptionHandler;
import com.bookworm.common.web.PageResponse;
import com.bookworm.common.web.StrictJsonConfig;
import com.bookworm.common.web.TraceIdFilter;
import com.bookworm.testsupport.WebSliceSecurity;
import com.bookworm.user.UserService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Web-layer behaviour of the admin catalogue API: security, validation, strict JSON and response shape. */
@WebMvcTest({AdminBookController.class, AdminCategoryController.class, AdminAuthorController.class})
@Import({GlobalExceptionHandler.class, TraceIdFilter.class, StrictJsonConfig.class})
@WebSliceSecurity
class AdminCatalogueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminBookService bookService;

    @MockitoBean
    private AdminCategoryService categoryService;

    @MockitoBean
    private AdminAuthorService authorService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("7")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static JwtRequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject("8")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static final String VALID_BOOK = """
            {"isbn":"978-0-306-40615-7","title":"A Title","price":12.50,"categoryId":1,"authorIds":[1,2]}""";

    private static AdminBookResponse bookResponse() {
        return new AdminBookResponse(5L, "9780306406157", "A Title", null, List.of(),
                new AdminBookResponse.Category(1L, "C", "c"), new BigDecimal("12.50"), null, null, null,
                false, 0, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ---- authentication and authorization on every admin route ------------------------------------

    private static final List<String[]> ROUTES = List.of(
            new String[] {"POST", "/api/v1/admin/books"}, new String[] {"GET", "/api/v1/admin/books"},
            new String[] {"GET", "/api/v1/admin/books/1"}, new String[] {"PUT", "/api/v1/admin/books/1"},
            new String[] {"PATCH", "/api/v1/admin/books/1/active"},
            new String[] {"POST", "/api/v1/admin/categories"}, new String[] {"GET", "/api/v1/admin/categories"},
            new String[] {"GET", "/api/v1/admin/categories/1"}, new String[] {"PUT", "/api/v1/admin/categories/1"},
            new String[] {"POST", "/api/v1/admin/authors"}, new String[] {"GET", "/api/v1/admin/authors"},
            new String[] {"GET", "/api/v1/admin/authors/1"}, new String[] {"PUT", "/api/v1/admin/authors/1"});

    private static MockHttpServletRequestBuilder request(String method, String path) {
        return switch (method) {
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "PATCH" -> patch(path);
            default -> get(path);
        };
    }

    @Test
    void everyAdminRouteRejectsAnonymousCallersWith401() throws Exception {
        for (String[] route : ROUTES) {
            mockMvc.perform(json(request(route[0], route[1]), "{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        verifyNoInteractions(bookService, categoryService, authorService);
    }

    @Test
    void everyAdminRouteRejectsACustomerWith403() throws Exception {
        for (String[] route : ROUTES) {
            mockMvc.perform(json(request(route[0], route[1]), "{}").with(customer()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        verifyNoInteractions(bookService, categoryService, authorService);
    }

    @Test
    void deleteIsNotAvailableToAnyone() throws Exception {
        for (String path : List.of("/api/v1/admin/books/1", "/api/v1/admin/categories/1",
                "/api/v1/admin/authors/1", "/api/v1/admin/books/1/active")) {
            mockMvc.perform(delete(path).with(admin())).andExpect(status().isMethodNotAllowed());
            mockMvc.perform(delete(path).with(customer())).andExpect(status().isForbidden());
            mockMvc.perform(delete(path)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(bookService, categoryService, authorService);
    }

    @Test
    void thereIsNoStockEndpoint() throws Exception {
        for (String path : List.of("/api/v1/admin/books/1/stock", "/api/v1/admin/stock")) {
            for (var method : List.of("PUT", "PATCH", "POST")) {
                mockMvc.perform(json(request(method, path), "{\"stockQuantity\":5}").with(admin()))
                        .andExpect(status().isNotFound());
            }
        }
        verifyNoInteractions(bookService);
    }

    // ---- books ------------------------------------------------------------------------------------

    @Test
    void createBookReturns201WithLocationAndUsesTheTokenSubjectAsAdminId() throws Exception {
        when(bookService.create(eq(7L), any(BookCreateRequest.class))).thenReturn(bookResponse());

        mockMvc.perform(json(post("/api/v1/admin/books"), VALID_BOOK).with(admin()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/books/5"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.stockQuantity").value(0));
    }

    @Test
    void createBookRejectsUnknownFieldsAndDoesNotEchoTheirNames() throws Exception {
        for (String extra : List.of("\"stockQuantity\":99", "\"active\":true", "\"role\":\"ADMIN\"",
                "\"id\":3", "\"version\":1")) {
            String body = VALID_BOOK.replace("{", "{" + extra + ",");
            mockMvc.perform(json(post("/api/v1/admin/books"), body).with(admin()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString(extra.split(":")[0].replace("\"", "")))));
        }
        verifyNoInteractions(bookService);
    }

    @Test
    void createBookValidatesEveryField() throws Exception {
        String longTitle = "t".repeat(301);
        String longDescription = "d".repeat(10001);
        List<String> bad = List.of(
                "{\"title\":\"x\",\"price\":1,\"categoryId\":1,\"authorIds\":[1]}",                       // no isbn
                VALID_BOOK.replace("\"A Title\"", "\"\""),                                                  // blank title
                VALID_BOOK.replace("\"A Title\"", "\"" + longTitle + "\""),
                VALID_BOOK.replace("12.50", "0"),                                                           // price
                VALID_BOOK.replace("12.50", "-1"),
                VALID_BOOK.replace("12.50", "12.505"),                                                      // 3 decimals
                VALID_BOOK.replace("12.50", "12345678901.00"),                                              // 11 integer digits
                VALID_BOOK.replace("\"categoryId\":1", "\"categoryId\":0"),
                VALID_BOOK.replace("\"categoryId\":1,", ""),                                                // no category
                VALID_BOOK.replace("[1,2]", "[]"),                                                          // no authors
                VALID_BOOK.replace("[1,2]", "[1,2,3,4,5,6,7,8,9,10,11]"),                                   // 11 authors
                VALID_BOOK.replace("[1,2]", "[0]"),
                VALID_BOOK.replace("[1,2]", "[null]"),
                VALID_BOOK.replace("}", ",\"description\":\"" + longDescription + "\"}"),
                VALID_BOOK.replace("}", ",\"publisher\":\"" + "p".repeat(201) + "\"}"),
                VALID_BOOK.replace("}", ",\"coverUrl\":\"https://x.test/" + "c".repeat(500) + "\"}"),
                VALID_BOOK.replace("}", ",\"publishedDate\":\"not-a-date\"}"),
                VALID_BOOK.replace("978-0-306-40615-7", "9".repeat(21)));
        for (String body : bad) {
            mockMvc.perform(json(post("/api/v1/admin/books"), body).with(admin()))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(bookService);
    }

    @Test
    void updateBookRequiresTheVersion() throws Exception {
        mockMvc.perform(json(put("/api/v1/admin/books/5"), VALID_BOOK).with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(bookService);
    }

    @Test
    void updateBookPassesTheIdVersionAndAdminId() throws Exception {
        when(bookService.update(eq(7L), eq(5L), any(BookUpdateRequest.class))).thenReturn(bookResponse());

        mockMvc.perform(json(put("/api/v1/admin/books/5"), VALID_BOOK.replace("{", "{\"version\":3,"))
                        .with(admin()))
                .andExpect(status().isOk());

        verify(bookService).update(eq(7L), eq(5L), any(BookUpdateRequest.class));
    }

    @Test
    void updateBookRejectsStockAndActiveFields() throws Exception {
        for (String extra : List.of("\"stockQuantity\":1", "\"active\":false")) {
            mockMvc.perform(json(put("/api/v1/admin/books/5"),
                            VALID_BOOK.replace("{", "{\"version\":3," + extra + ",")).with(admin()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        verifyNoInteractions(bookService);
    }

    @Test
    void activeFlagIsRequiredAndMustBeABoolean() throws Exception {
        for (String body : List.of("{}", "{\"active\":null}", "{\"active\":\"yes\"}", "{\"active\":1}",
                "{\"active\":true,\"stockQuantity\":1}", "{\"active\":true,\"version\":2}", "[]", "")) {
            int actual = mockMvc.perform(json(patch("/api/v1/admin/books/5/active"), body).with(admin()))
                    .andReturn().getResponse().getStatus();
            org.assertj.core.api.Assertions.assertThat(actual).as("body: " + body).isEqualTo(400);
        }
        verifyNoInteractions(bookService);
    }

    @Test
    void activeFlagIsPassedToTheService() throws Exception {
        when(bookService.setActive(eq(7L), eq(5L), any(BookActiveRequest.class))).thenReturn(bookResponse());

        mockMvc.perform(json(patch("/api/v1/admin/books/5/active"), "{\"active\":true}").with(admin()))
                .andExpect(status().isOk());

        verify(bookService).setActive(eq(7L), eq(5L), any(BookActiveRequest.class));
    }

    @Test
    void bookPathIdsMustBePositiveNumbers() throws Exception {
        mockMvc.perform(get("/api/v1/admin/books/0").with(admin())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/books/abc").with(admin())).andExpect(status().isBadRequest());
        mockMvc.perform(json(patch("/api/v1/admin/books/-1/active"), "{\"active\":true}").with(admin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(bookService);
    }

    @Test
    void bookSearchUsesDefaultsAndParsesSortAndFilters() throws Exception {
        when(bookService.search(any(), any(), any(), eq(0), eq(20), eq("id"), eq(Sort.Direction.ASC)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
        when(bookService.search(eq("dune"), eq(3L), eq(false), eq(2), eq(5), eq("updatedAt"),
                eq(Sort.Direction.DESC))).thenReturn(new PageResponse<>(List.of(), 2, 5, 0, 0));

        mockMvc.perform(get("/api/v1/admin/books").with(admin())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/books").with(admin()).param("q", "dune").param("categoryId", "3")
                        .param("active", "false").param("page", "2").param("size", "5")
                        .param("sort", "updatedAt,desc"))
                .andExpect(status().isOk());
    }

    @Test
    void bookSearchRejectsBadParameters() throws Exception {
        List<String[]> bad = List.of(
                new String[] {"sort", "stockQuantity"}, new String[] {"sort", "password"},
                new String[] {"sort", "title,sideways"}, new String[] {"sort", "title; drop table books"},
                new String[] {"size", "0"}, new String[] {"size", "101"}, new String[] {"page", "-1"},
                new String[] {"categoryId", "0"}, new String[] {"categoryId", "x"},
                new String[] {"active", "maybe"}, new String[] {"q", "q".repeat(101)});
        for (String[] p : bad) {
            mockMvc.perform(get("/api/v1/admin/books").with(admin()).param(p[0], p[1]))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(bookService);
    }

    // ---- categories -------------------------------------------------------------------------------

    @Test
    void createCategoryReturns201WithLocation() throws Exception {
        when(categoryService.create(eq(7L), any(CategoryRequest.class)))
                .thenReturn(new AdminCategoryResponse(9L, "Poetry", "poetry", 0));

        mockMvc.perform(json(post("/api/v1/admin/categories"), "{\"name\":\"Poetry\"}").with(admin()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/categories/9"))
                .andExpect(jsonPath("$.slug").value("poetry"));
    }

    @Test
    void categoryRequestRejectsSlugOverridesAndBadNames() throws Exception {
        for (String body : List.of("{\"name\":\"Poetry\",\"slug\":\"evil\"}", "{\"name\":\"Poetry\",\"id\":1}",
                "{}", "{\"name\":\"\"}", "{\"name\":\"   \"}", "{\"name\":null}",
                "{\"name\":\"" + "n".repeat(401) + "\"}")) {
            mockMvc.perform(json(post("/api/v1/admin/categories"), body).with(admin()))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(json(put("/api/v1/admin/categories/1"), body).with(admin()))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(categoryService);
    }

    @Test
    void categoryListIsUnpaged() throws Exception {
        when(categoryService.list()).thenReturn(List.of(new AdminCategoryResponse(1L, "A", "a", 2)));

        mockMvc.perform(get("/api/v1/admin/categories").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bookCount").value(2));
    }

    // ---- authors ----------------------------------------------------------------------------------

    @Test
    void createAuthorReturns201WithLocation() throws Exception {
        when(authorService.create(eq(7L), any(AuthorRequest.class))).thenReturn(new AdminAuthorResponse(4L, "Ann", 0));

        mockMvc.perform(json(post("/api/v1/admin/authors"), "{\"name\":\"Ann\"}").with(admin()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/authors/4"));
    }

    @Test
    void authorRequestRejectsUnknownFieldsAndBadNames() throws Exception {
        for (String body : List.of("{\"name\":\"Ann\",\"bookCount\":5}", "{}", "{\"name\":\"\"}",
                "{\"name\":\"" + "n".repeat(801) + "\"}")) {
            mockMvc.perform(json(post("/api/v1/admin/authors"), body).with(admin()))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(authorService);
    }

    @Test
    void authorSearchValidatesSortAndPaging() throws Exception {
        for (String[] p : List.of(new String[] {"sort", "bookCount"}, new String[] {"sort", "name,up"},
                new String[] {"size", "101"}, new String[] {"page", "-1"})) {
            mockMvc.perform(get("/api/v1/admin/authors").with(admin()).param(p[0], p[1]))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(authorService);
    }
}
