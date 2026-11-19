package com.bookworm.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookworm.common.security.SecurityProperties;
import com.bookworm.user.UserService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The bootstrap against the real database: insert-only, idempotent, never promotes or overwrites. */
class AdminBootstrapIT extends AbstractAuthIT {

    private static final String ADMIN_PASSWORD = "bootstrap admin password";

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private void bootstrap(String email, String password) {
        SecurityProperties props = new SecurityProperties(4,
                new SecurityProperties.Jwt("unused", "bookworm", Duration.ofMinutes(60)),
                new SecurityProperties.Lockout(5, Duration.ofMinutes(15)),
                new SecurityProperties.Admin(email, password));
        new AdminBootstrap(props, userService, passwordEncoder).run(new DefaultApplicationArguments());
    }

    private String storedHash(String email) {
        return jdbc.queryForObject("select password_hash from users where email = ?", String.class, email);
    }

    @Test
    void createsOneAdministratorWhoCanLogInAndIsIdempotentOnRepeat() throws Exception {
        String email = uniqueEmail();

        bootstrap(email, ADMIN_PASSWORD);
        String firstHash = storedHash(email);
        bootstrap(email, ADMIN_PASSWORD);
        bootstrap(email, "a different admin password");

        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select role from users where email = ?", String.class, email))
                .isEqualTo("ADMIN");
        assertThat(storedHash(email)).isEqualTo(firstHash).doesNotContain(ADMIN_PASSWORD);
        assertThat(login(email, ADMIN_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void neverPromotesAnExistingCustomerOrOverwritesTheirCredentials() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        String hashBefore = storedHash(email);

        bootstrap(email, ADMIN_PASSWORD);

        assertThat(jdbc.queryForObject("select role from users where email = ?", String.class, email))
                .isEqualTo("CUSTOMER");
        assertThat(storedHash(email)).isEqualTo(hashBefore);
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(login(email, ADMIN_PASSWORD).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void neverChangesAnExistingAdminsPasswordOrState() throws Exception {
        String email = uniqueEmail();
        bootstrap(email, ADMIN_PASSWORD);
        jdbc.update("update users set enabled = false, first_name = 'Manual' where email = ?", email);
        String hashBefore = storedHash(email);

        bootstrap(email, "someone elses new password");

        assertThat(storedHash(email)).isEqualTo(hashBefore);
        assertThat(jdbc.queryForObject("select enabled from users where email = ?", Boolean.class, email)).isFalse();
        assertThat(jdbc.queryForObject("select first_name from users where email = ?", String.class, email))
                .isEqualTo("Manual");
    }

    @Test
    void doesNothingWhenNoCredentialsAreConfigured() {
        int before = jdbc.queryForObject("select count(*) from users", Integer.class);

        bootstrap(null, null);
        bootstrap("", "");

        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(before);
    }
}
