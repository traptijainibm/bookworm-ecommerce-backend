package com.bookworm.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bookworm.common.security.SecurityProperties;
import com.bookworm.user.UserService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminBootstrapTest {

    private static final String PASSWORD = "an admin password value";

    private UserService userService;
    private PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("{bcrypt}adminhash");
    }

    private AdminBootstrap bootstrap(String email, String password) {
        SecurityProperties props = new SecurityProperties(4,
                new SecurityProperties.Jwt("unused", "bookworm", Duration.ofMinutes(60)),
                new SecurityProperties.Lockout(5, Duration.ofMinutes(15)),
                new SecurityProperties.Admin(email, password));
        return new AdminBootstrap(props, userService, encoder);
    }

    private void run(AdminBootstrap bootstrap) {
        bootstrap.run(new DefaultApplicationArguments());
    }

    @Test
    void nothingIsCreatedWhenNeitherVariableIsSet() {
        run(bootstrap(null, null));
        run(bootstrap("", ""));
        run(bootstrap("  ", "  "));

        verifyNoInteractions(userService);
        verifyNoInteractions(encoder);
    }

    @Test
    void startupFailsWhenOnlyOneVariableIsSetWithoutEchoingTheValue() {
        assertThatThrownBy(() -> run(bootstrap("boss@example.test", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("boss@example.test");
        assertThatThrownBy(() -> run(bootstrap(null, PASSWORD)))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(PASSWORD);
        verifyNoInteractions(userService);
    }

    @Test
    void startupFailsForAnInvalidEmailOrAWeakPasswordWithoutEchoingEither() {
        assertThatThrownBy(() -> run(bootstrap("not-an-email", PASSWORD)))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("not-an-email")
                .hasMessageNotContaining(PASSWORD);
        assertThatThrownBy(() -> run(bootstrap("boss@example.test", "short")))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("short");
        verify(userService, never()).createAdminIfAbsent(anyString(), anyString());
    }

    @Test
    void createsTheAdministratorWithAHashedPasswordWhenTheEmailIsFree() {
        when(userService.emailExists("boss@example.test")).thenReturn(false);
        when(userService.createAdminIfAbsent("boss@example.test", "{bcrypt}adminhash")).thenReturn(true);

        run(bootstrap("  Boss@Example.TEST ", PASSWORD));

        verify(encoder).encode(PASSWORD);
        verify(userService).createAdminIfAbsent("boss@example.test", "{bcrypt}adminhash");
    }

    @Test
    void anExistingAccountIsNeverPromotedOrGivenNewCredentials() {
        // The email already belongs to an account (for example a CUSTOMER): nothing may be written.
        when(userService.emailExists("boss@example.test")).thenReturn(true);

        run(bootstrap("boss@example.test", PASSWORD));

        verify(userService, never()).createAdminIfAbsent(anyString(), anyString());
        verify(encoder, never()).encode(any());
    }

    @Test
    void runningTwiceIsIdempotent() {
        when(userService.emailExists("boss@example.test")).thenReturn(false, true);
        when(userService.createAdminIfAbsent(anyString(), anyString())).thenReturn(true);
        AdminBootstrap bootstrap = bootstrap("boss@example.test", PASSWORD);

        run(bootstrap);
        run(bootstrap);

        verify(userService).createAdminIfAbsent(anyString(), anyString()); // exactly once
    }

    @Test
    void aRaceWithAnotherInsertIsAbsorbedByTheInsertOnlyStatement() {
        when(userService.emailExists("boss@example.test")).thenReturn(false);
        when(userService.createAdminIfAbsent(anyString(), anyString())).thenReturn(false);

        run(bootstrap("boss@example.test", PASSWORD)); // must not throw

        verify(userService).createAdminIfAbsent("boss@example.test", "{bcrypt}adminhash");
    }
}
