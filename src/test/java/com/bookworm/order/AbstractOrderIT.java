package com.bookworm.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestJwtSecretInitializer.class)
@Import({TestClockConfig.class, AdminProbe.class})
abstract class AbstractOrderIT {

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
                Long.class, NAME_PREFIX + " Order Category", "itcart-order-category");
        authorId = jdbc.queryForObject("insert into authors (name) values (?) returning id", Long.class,
                NAME_PREFIX + " Order Author");
        isbnCounter = 0;
    }

    @AfterEach
    void tearDownFixtures() {
        cleanUp();
    }

    protected void cleanUp() {
        jdbc.update("delete from users where email like ?", EMAIL_PREFIX + "%");
        jdbc.update("delete from book_authors where book_id in (select id from books where isbn like ?)",
                ISBN_PREFIX + "%");
        jdbc.update("delete from books where isbn like ?", ISBN_PREFIX + "%");
        jdbc.update("delete from authors where name like ?", NAME_PREFIX + "%");
        jdbc.update("delete from categories where name like ?", NAME_PREFIX + "%");
        clock.reset();
    }

    protected long insertUser(String role, boolean enabled) {
        String email = EMAIL_PREFIX + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        return jdbc.queryForObject("""
                insert into users (email, password_hash, first_name, last_name, role, enabled)
                values (?, 'not-a-real-hash', 'It', 'Order', ?, ?) returning id
                """, Long.class, email, role, enabled);
    }

    protected String token(long userId, String roleClaim) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("bookworm").subject(String.valueOf(userId))
                .claim("role", roleClaim).issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    protected long book(String title, String price, int stock, boolean active) {
        String isbn = ISBN_PREFIX + String.format("%07d", ++isbnCounter);
        Long id = jdbc.queryForObject("""
                insert into books (isbn, title, description, price, category_id, stock_quantity, active)
                values (?, ?, 'IT order fixture', ?, ?, ?, ?) returning id
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

    protected int cartLines(long userId) {
        return jdbc.queryForObject("""
                select count(*) from cart_items ci join carts c on c.id = ci.cart_id where c.user_id = ?
                """, Integer.class, userId);
    }

    protected int orderCount(long userId) {
        return jdbc.queryForObject("select count(*) from orders where user_id = ?", Integer.class, userId);
    }

    protected int orderItemsCount(String orderNumber) {
        return jdbc.queryForObject("""
                select count(*) from order_items oi join orders o on o.id = oi.order_id where o.order_number = ?
                """, Integer.class, orderNumber);
    }

    protected int orderStatusHistoryCount(String orderNumber) {
        return jdbc.queryForObject("""
                select count(*) from order_status_history osh join orders o on o.id = osh.order_id where o.order_number = ?
                """, Integer.class, orderNumber);
    }

    protected int paymentCount(String orderNumber) {
        return jdbc.queryForObject("""
                select count(*) from payments p join orders o on o.id = p.order_id where o.order_number = ?
                """, Integer.class, orderNumber);
    }

    protected MvcResult addToCart(String token, long bookId, int quantity) throws Exception {
        return mockMvc.perform(bearer(json(post("/api/v1/me/cart/items"),
                "{\"bookId\":" + bookId + ",\"quantity\":" + quantity + "}"), token)).andReturn();
    }

    protected MvcResult checkout(String token, String idempotencyKey, String body) throws Exception {
        MockHttpServletRequestBuilder builder = post("/api/v1/me/orders");
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(bearer(json(builder, body), token)).andReturn();
    }

    protected MvcResult getOrderByNumber(String token, String orderNumber) throws Exception {
        return mockMvc.perform(bearer(get("/api/v1/me/orders/" + orderNumber), token)).andReturn();
    }

    protected static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected static <T> T read(MvcResult result, String jsonPath) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
    }

    protected static String validCheckoutJson(String token, long points) {
        return """
                {
                  "shippingAddress": {
                    "fullName": "Jane Doe",
                    "phone": "9876543210",
                    "addressLine1": "123 Main St",
                    "addressLine2": "Apt 4B",
                    "city": "Bengaluru",
                    "state": "Karnataka",
                    "postalCode": "560001",
                    "country": "India"
                  },
                  "paymentToken": "%s",
                  "redeemPoints": %d
                }
                """.formatted(token, points);
    }
}
