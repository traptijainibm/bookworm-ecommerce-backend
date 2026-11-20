package com.bookworm.catalogue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.bookworm.testsupport.AdminProbe;
import com.bookworm.testsupport.MutableClock;
import com.bookworm.testsupport.TestClockConfig;
import com.bookworm.testsupport.TestJwtSecretInitializer;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared setup for the admin catalogue integration tests against the real PostgreSQL {@code bookworm_test}
 * database. They are NOT {@code @Transactional}: the security layer reloads the user from committed rows on every
 * request, and the concurrency tests need real commits. Everything they create is recognisable and is deleted
 * before and after each test: users {@code itadm-%}, authors and categories named {@code Itadm%}, and books whose
 * ISBN starts with {@code 978999}. The configuration matches {@code AbstractAuthIT}, so one Spring context is shared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestJwtSecretInitializer.class)
@Import({TestClockConfig.class, AdminProbe.class})
abstract class AbstractAdminIT {

    static final String NAME_PREFIX = "Itadm";
    static final String EMAIL_PREFIX = "itadm-";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JwtEncoder jwtEncoder;

    @Autowired
    protected MutableClock clock;

    protected long adminId;
    protected long customerId;
    protected String adminToken;
    protected String customerToken;

    @BeforeEach
    void setUpFixtures() {
        cleanUp();
        adminId = insertUser("ADMIN", true);
        customerId = insertUser("CUSTOMER", true);
        adminToken = token(adminId, "ADMIN");
        customerToken = token(customerId, "CUSTOMER");
    }

    @AfterEach
    void tearDownFixtures() {
        cleanUp();
    }

    protected void cleanUp() {
        jdbc.update("delete from book_authors where book_id in (select id from books where isbn like '978999%')");
        jdbc.update("delete from books where isbn like '978999%'");
        // Case-insensitive: several tests deliberately create names such as "ITADM same AUTHOR".
        jdbc.update("delete from authors where lower(name) like ?", NAME_PREFIX.toLowerCase() + "%");
        jdbc.update("delete from categories where lower(name) like ?", NAME_PREFIX.toLowerCase() + "%");
        jdbc.update("delete from users where email like ?", EMAIL_PREFIX + "%");
        clock.reset();
    }

    protected long insertUser(String role, boolean enabled) {
        String email = EMAIL_PREFIX + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        // The hash is a placeholder: these users never log in, tokens are minted directly.
        return jdbc.queryForObject("""
                insert into users (email, password_hash, first_name, last_name, role, enabled)
                values (?, 'not-a-real-hash', 'It', 'Adm', ?, ?) returning id
                """, Long.class, email, role, enabled);
    }

    protected String token(long userId, String roleClaim) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("bookworm").subject(String.valueOf(userId))
                .claim("role", roleClaim).issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    // ---- request helpers ---------------------------------------------------------------------------

    protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + adminToken);
    }

    protected MockHttpServletRequestBuilder asCustomer(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + customerToken);
    }

    protected static String read(MvcResult result, String path) throws Exception {
        Object value = JsonPath.read(result.getResponse().getContentAsString(), path);
        return String.valueOf(value);
    }

    protected static long readLong(MvcResult result, String path) throws Exception {
        return Long.parseLong(read(result, path));
    }

    protected MockHttpServletRequestBuilder adminGet(String path) {
        return asAdmin(get(path));
    }

    protected MockHttpServletRequestBuilder adminPut(String path, String body) {
        return asAdmin(json(put(path), body));
    }

    // ---- fixtures created through the real API -------------------------------------------------------

    protected long createAuthor(String name) throws Exception {
        MvcResult result = mockMvc.perform(asAdmin(json(post("/api/v1/admin/authors"),
                "{\"name\":\"" + name + "\"}"))).andReturn();
        if (result.getResponse().getStatus() != 201) {
            throw new IllegalStateException("author fixture failed: " + result.getResponse().getStatus());
        }
        return readLong(result, "$.id");
    }

    protected MvcResult createCategoryResult(String name) throws Exception {
        return mockMvc.perform(asAdmin(json(post("/api/v1/admin/categories"), "{\"name\":\"" + name + "\"}")))
                .andReturn();
    }

    protected long createCategory(String name) throws Exception {
        MvcResult result = createCategoryResult(name);
        if (result.getResponse().getStatus() != 201) {
            throw new IllegalStateException("category fixture failed: " + result.getResponse().getStatus());
        }
        return readLong(result, "$.id");
    }

    protected static String bookJson(String isbn, String title, long categoryId, long... authorIds) {
        StringBuilder authors = new StringBuilder();
        for (int i = 0; i < authorIds.length; i++) {
            authors.append(i == 0 ? "" : ",").append(authorIds[i]);
        }
        return "{\"isbn\":\"" + isbn + "\",\"title\":\"" + title + "\",\"price\":19.99,\"categoryId\":" + categoryId
                + ",\"authorIds\":[" + authors + "]}";
    }

    protected static String bookUpdateJson(long version, String isbn, String title, long categoryId,
                                           long... authorIds) {
        return bookJson(isbn, title, categoryId, authorIds).replace("{", "{\"version\":" + version + ",");
    }

    protected MvcResult createBookResult(String isbn, String title, long categoryId, long... authorIds)
            throws Exception {
        return mockMvc.perform(asAdmin(json(post("/api/v1/admin/books"),
                bookJson(isbn, title, categoryId, authorIds)))).andReturn();
    }

    protected long createBook(String isbn, String title, long categoryId, long... authorIds) throws Exception {
        MvcResult result = createBookResult(isbn, title, categoryId, authorIds);
        if (result.getResponse().getStatus() != 201) {
            throw new IllegalStateException("book fixture failed: " + result.getResponse().getStatus() + " "
                    + result.getResponse().getContentAsString());
        }
        return readLong(result, "$.id");
    }

    protected void setActive(long bookId, boolean active) throws Exception {
        MvcResult result = mockMvc.perform(asAdmin(json(patch("/api/v1/admin/books/" + bookId + "/active"),
                "{\"active\":" + active + "}"))).andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new IllegalStateException("activate fixture failed: " + result.getResponse().getStatus());
        }
    }

    /** A valid ISBN-13 in the reserved {@code 978999} test range (so cleanup can find it). */
    protected static String isbn(int n) {
        String twelve = "978999" + String.format("%06d", n);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (twelve.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return twelve + ((10 - sum % 10) % 10);
    }

    /** The ISBN-10 form of {@link #isbn(int)}; only valid for 978-prefixed ISBNs. */
    protected static String isbn10(String isbn13) {
        String nine = isbn13.substring(3, 12);
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += (nine.charAt(i) - '0') * (10 - i);
        }
        int check = (11 - sum % 11) % 11;
        return nine + (check == 10 ? "X" : String.valueOf(check));
    }
}
