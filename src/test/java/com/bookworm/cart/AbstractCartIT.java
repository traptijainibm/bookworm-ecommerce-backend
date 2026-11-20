package com.bookworm.cart;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.bookworm.testsupport.AdminProbe;
import com.bookworm.testsupport.MutableClock;
import com.bookworm.testsupport.TestClockConfig;
import com.bookworm.testsupport.TestJwtSecretInitializer;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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
 * Shared setup for the cart integration tests against the real PostgreSQL {@code bookworm_test} database. They
 * are NOT {@code @Transactional}: the security layer reloads the user from committed rows on every request and the
 * cart row lock needs real commits. Everything they create is recognisable and is deleted before and after each
 * test: users {@code itcart-%} (their carts and cart lines cascade), books whose ISBN starts with {@code 978998},
 * and authors and categories named {@code Itcart%}. The configuration matches the other IT bases, so one Spring
 * context is shared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestJwtSecretInitializer.class)
@Import({TestClockConfig.class, AdminProbe.class})
abstract class AbstractCartIT {

    static final String NAME_PREFIX = "Itcart";
    static final String EMAIL_PREFIX = "itcart-";
    static final String ISBN_PREFIX = "978998";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JwtEncoder jwtEncoder;

    @Autowired
    protected MutableClock clock;

    protected long customerId;
    protected long otherCustomerId;
    protected long adminId;
    protected String customerToken;
    protected String otherCustomerToken;
    protected String adminToken;

    private long categoryId;
    private long authorId;
    private int isbnCounter;

    @BeforeEach
    void setUpFixtures() {
        cleanUp();
        customerId = insertUser("CUSTOMER", true);
        otherCustomerId = insertUser("CUSTOMER", true);
        adminId = insertUser("ADMIN", true);
        customerToken = token(customerId, "CUSTOMER");
        otherCustomerToken = token(otherCustomerId, "CUSTOMER");
        adminToken = token(adminId, "ADMIN");
        categoryId = jdbc.queryForObject("insert into categories (name, slug) values (?, ?) returning id",
                Long.class, NAME_PREFIX + " Category", "itcart-category");
        authorId = jdbc.queryForObject("insert into authors (name) values (?) returning id", Long.class,
                NAME_PREFIX + " Author");
        isbnCounter = 0;
    }

    @AfterEach
    void tearDownFixtures() {
        cleanUp();
    }

    /** Users first: their carts and lines cascade, which frees the books that the lines reference. */
    protected void cleanUp() {
        jdbc.update("delete from users where email like ?", EMAIL_PREFIX + "%");
        jdbc.update("delete from book_authors where book_id in (select id from books where isbn like ?)",
                ISBN_PREFIX + "%");
        jdbc.update("delete from books where isbn like ?", ISBN_PREFIX + "%");
        jdbc.update("delete from authors where name like ?", NAME_PREFIX + "%");
        jdbc.update("delete from categories where name like ?", NAME_PREFIX + "%");
        clock.reset();
    }

    // ---- fixtures ----------------------------------------------------------------------------------------

    protected long insertUser(String role, boolean enabled) {
        String email = EMAIL_PREFIX + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        // The hash is a placeholder: these users never log in, tokens are minted directly.
        return jdbc.queryForObject("""
                insert into users (email, password_hash, first_name, last_name, role, enabled)
                values (?, 'not-a-real-hash', 'It', 'Cart', ?, ?) returning id
                """, Long.class, email, role, enabled);
    }

    protected String token(long userId, String roleClaim) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("bookworm").subject(String.valueOf(userId))
                .claim("role", roleClaim).issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    /** Inserts a book with JDBC (stock is written only here, in test fixtures) and returns its id. */
    protected long book(String title, String price, int stock, boolean active) {
        String isbn = ISBN_PREFIX + String.format("%07d", ++isbnCounter);
        Long id = jdbc.queryForObject("""
                insert into books (isbn, title, description, price, category_id, stock_quantity, active)
                values (?, ?, 'IT cart fixture', ?, ?, ?, ?) returning id
                """, Long.class, isbn, NAME_PREFIX + " " + title, new BigDecimal(price), categoryId, stock, active);
        jdbc.update("insert into book_authors (book_id, author_id) values (?, ?)", id, authorId);
        return id;
    }

    protected void setBookActive(long bookId, boolean active) {
        jdbc.update("update books set active = ? where id = ?", active, bookId);
    }

    protected int stockOf(long bookId) {
        return jdbc.queryForObject("select stock_quantity from books where id = ?", Integer.class, bookId);
    }

    protected int cartRows(long userId) {
        return jdbc.queryForObject("select count(*) from carts where user_id = ?", Integer.class, userId);
    }

    protected int lineRows(long userId) {
        return jdbc.queryForObject("""
                select count(*) from cart_items ci join carts c on c.id = ci.cart_id where c.user_id = ?
                """, Integer.class, userId);
    }

    protected int quantityOf(long userId, long bookId) {
        return jdbc.queryForObject("""
                select ci.quantity from cart_items ci join carts c on c.id = ci.cart_id
                where c.user_id = ? and ci.book_id = ?
                """, Integer.class, userId, bookId);
    }

    // ---- request helpers -----------------------------------------------------------------------------------

    protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    protected MockHttpServletRequestBuilder asCustomer(MockHttpServletRequestBuilder request) {
        return bearer(request, customerToken);
    }

    protected MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return bearer(request, adminToken);
    }

    protected MvcResult addToCart(String token, long bookId, int quantity) throws Exception {
        return mockMvc.perform(bearer(json(post("/api/v1/me/cart/items"),
                "{\"bookId\":" + bookId + ",\"quantity\":" + quantity + "}"), token)).andReturn();
    }

    protected MvcResult getCart(String token) throws Exception {
        return mockMvc.perform(bearer(get("/api/v1/me/cart"), token)).andReturn();
    }

    protected MvcResult setQuantity(String token, long bookId, int quantity) throws Exception {
        return mockMvc.perform(bearer(json(put("/api/v1/me/cart/items/" + bookId),
                "{\"quantity\":" + quantity + "}"), token)).andReturn();
    }

    protected MvcResult removeLine(String token, long bookId) throws Exception {
        return mockMvc.perform(bearer(delete("/api/v1/me/cart/items/" + bookId), token)).andReturn();
    }

    protected MvcResult clearCart(String token) throws Exception {
        return mockMvc.perform(bearer(delete("/api/v1/me/cart/items"), token)).andReturn();
    }

    protected static String read(MvcResult result, String path) throws Exception {
        return String.valueOf((Object) JsonPath.read(result.getResponse().getContentAsString(), path));
    }
}
