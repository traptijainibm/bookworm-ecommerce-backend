package com.bookworm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class RegistrationAndLoginIT extends AbstractAuthIT {

    // ---- registration -----------------------------------------------------------------------------

    @Test
    void registrationCreatesACustomerAndNeverReturnsSecrets() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(jsonPost("/api/v1/auth/register", json("email", email.toUpperCase(), "password", PASSWORD,
                        "firstName", " Ada ", "lastName", "Reader")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.firstName").value("Ada"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.failedLoginAttempts").doesNotExist())
                .andExpect(jsonPath("$.lockedUntil").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());

        String stored = jdbc.queryForObject("select password_hash from users where email = ?", String.class, email);
        assertThat(stored).startsWith("{bcrypt}$2").doesNotContain(PASSWORD);
        assertThat(jdbc.queryForObject("select role from users where email = ?", String.class, email))
                .isEqualTo("CUSTOMER");
    }

    @Test
    void aRoleSuppliedInTheRequestIsIgnored() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(jsonPost("/api/v1/auth/register", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                        + "\",\"firstName\":\"Eve\",\"lastName\":\"Hacker\",\"role\":\"ADMIN\",\"enabled\":false,"
                        + "\"id\":1,\"failedLoginAttempts\":-5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));

        assertThat(jdbc.queryForObject("select role from users where email = ?", String.class, email))
                .isEqualTo("CUSTOMER");
        assertThat(jdbc.queryForObject("select enabled from users where email = ?", Boolean.class, email)).isTrue();
    }

    @Test
    void aDuplicateEmailIsRejectedCaseInsensitively() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);

        mockMvc.perform(jsonPost("/api/v1/auth/register", json("email", email.toUpperCase(), "password", PASSWORD,
                        "firstName", "Ada", "lastName", "Reader")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email))
                .isEqualTo(1);
    }

    @Test
    void weakPasswordsAndBadEmailsAreReportedAsFieldErrors() throws Exception {
        mockMvc.perform(jsonPost("/api/v1/auth/register", json("email", "nope", "password", "short",
                        "firstName", "Ada", "lastName", "Reader")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", org.hamcrest.Matchers.containsInAnyOrder("email", "password")));
        // The rejected password must not be echoed back.
        MvcResult result = mockMvc.perform(jsonPost("/api/v1/auth/register", json("email", uniqueEmail(),
                "password", "shortpass", "firstName", "Ada", "lastName", "Reader"))).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("shortpass");
    }

    @Test
    void overlongPasswordsAndMissingFieldsAreRejected() throws Exception {
        mockMvc.perform(jsonPost("/api/v1/auth/register", json("email", uniqueEmail(),
                        "password", "a".repeat(73), "firstName", "Ada", "lastName", "Reader")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(jsonPost("/api/v1/auth/register", "{}")).andExpect(status().isBadRequest());
        mockMvc.perform(jsonPost("/api/v1/auth/register", "{not json")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void sqlInjectionInTheEmailIsRejectedAndTheTableSurvives() throws Exception {
        mockMvc.perform(jsonPost("/api/v1/auth/register", json("email", "x'; drop table users;--@example.test",
                        "password", PASSWORD, "firstName", "Ada", "lastName", "Reader")))
                .andExpect(status().isBadRequest());
        // Injection in a name is stored as plain text, never executed.
        String email = uniqueEmail();
        mockMvc.perform(jsonPost("/api/v1/auth/register", json("email", email, "password", PASSWORD,
                        "firstName", "Robert'); DROP TABLE users;--", "lastName", "Tables")))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email))
                .isEqualTo(1);
    }

    @Test
    void theDatabaseRejectsAnUppercaseEmailOrUnknownRoleEvenIfTheApplicationWereBypassed() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "insert into users (email, password_hash, first_name, last_name, role) values (?,?,?,?,?)",
                "itauth-UPPER@example.test", "x", "A", "B", "CUSTOMER"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "insert into users (email, password_hash, first_name, last_name, role) values (?,?,?,?,?)",
                "itauth-role@example.test", "x", "A", "B", "SUPERUSER"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ---- login ------------------------------------------------------------------------------------

    @Test
    void loginReturnsABearerTokenWithAnHourLifetimeAndNoStoreHeaders() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);

        mockMvc.perform(jsonPost("/api/v1/auth/login", json("email", email.toUpperCase(), "password", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveIdenticalResponses() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);

        MvcResult wrongPassword = login(email, "this is not the password");
        MvcResult unknownEmail = login(uniqueEmail(), "this is not the password");

        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknownEmail.getResponse().getStatus()).isEqualTo(401);
        assertThat(field(wrongPassword, "$.code")).isEqualTo("INVALID_CREDENTIALS");
        assertThat(field(unknownEmail, "$.code")).isEqualTo("INVALID_CREDENTIALS");
        assertThat(field(wrongPassword, "$.detail")).isEqualTo(field(unknownEmail, "$.detail"));
        assertThat(wrongPassword.getResponse().getContentAsString()).doesNotContain(email);
    }

    @Test
    void aDisabledAccountCannotLogInAndGetsTheSameGenericError() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        jdbc.update("update users set enabled = false where email = ?", email);

        MvcResult result = login(email, PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(field(result, "$.code")).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void anOversizedPasswordIsRefusedQuicklyWithoutCountingAsAServerError() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);

        MvcResult result = login(email, "a".repeat(100_000));

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void loginWithMissingFieldsOrWrongContentTypeIsAClientError() throws Exception {
        mockMvc.perform(jsonPost("/api/v1/auth/login", "{}")).andExpect(status().isBadRequest());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content("email=a&password=b"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void aSuccessfulLoginResetsThePartialFailureCounter() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        login(email, "wrong password 1");
        login(email, "wrong password 2");
        assertThat(failedAttempts(email)).isEqualTo(2);

        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(200);

        assertThat(failedAttempts(email)).isZero();
        mockMvc.perform(me(tokenFrom(login(email, PASSWORD)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.email", not(containsString("password"))));
    }
}
