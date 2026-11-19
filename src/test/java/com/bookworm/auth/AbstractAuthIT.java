package com.bookworm.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * Shared setup for the authentication integration tests, which run against the real PostgreSQL
 * {@code bookworm_test} database. They need COMMITTED rows (the lockout bookkeeping runs in
 * {@code REQUIRES_NEW} transactions that cannot see an uncommitted test transaction), so these tests are not
 * {@code @Transactional}. Every account they create has an {@code itauth-} email and is deleted after each test.
 * All subclasses share one Spring context (identical configuration), so it starts only once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestJwtSecretInitializer.class)
@Import({TestClockConfig.class, AdminProbe.class})
abstract class AbstractAuthIT {

    static final String EMAIL_PREFIX = "itauth-";
    static final String PASSWORD = "correct horse battery";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JwtEncoder jwtEncoder;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("delete from users where email like ?", EMAIL_PREFIX + "%");
        clock.reset();
    }

    protected static String uniqueEmail() {
        return EMAIL_PREFIX + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    protected static String json(String... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(keyValues[i]).append("\":\"").append(keyValues[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }

    protected MockHttpServletRequestBuilder jsonPost(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected MvcResult register(String email, String password) throws Exception {
        return mockMvc.perform(jsonPost("/api/v1/auth/register", json("email", email, "password", password,
                "firstName", "Ada", "lastName", "Reader"))).andReturn();
    }

    protected MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(jsonPost("/api/v1/auth/login", json("email", email, "password", password)))
                .andReturn();
    }

    /** Registers a customer and returns a bearer token for it. */
    protected String registerAndLogin(String email) throws Exception {
        MvcResult registered = register(email, PASSWORD);
        if (registered.getResponse().getStatus() != 201) {
            throw new IllegalStateException("fixture registration failed: " + registered.getResponse().getStatus());
        }
        return tokenFrom(login(email, PASSWORD));
    }

    protected static String tokenFrom(MvcResult login) throws Exception {
        return JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
    }

    protected static String field(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    protected long userId(String email) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email);
    }

    protected int failedAttempts(String email) {
        return jdbc.queryForObject("select failed_login_attempts from users where email = ?", Integer.class, email);
    }

    protected MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    protected MockHttpServletRequestBuilder me(String token) {
        return bearer(get("/api/v1/me"), token);
    }

    /** A token signed with the application's real key (so only the claims are under test). */
    protected String signedToken(JwtClaimsSet claims) {
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    protected static JwtClaimsSet.Builder claims(String subject, String role) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer("bookworm").issuedAt(now).expiresAt(now.plusSeconds(3600));
        if (subject != null) {
            builder.subject(subject);
        }
        if (role != null) {
            builder.claim("role", role);
        }
        return builder;
    }
}
