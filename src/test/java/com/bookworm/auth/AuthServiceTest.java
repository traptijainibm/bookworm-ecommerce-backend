package com.bookworm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import com.bookworm.user.Credentials;
import com.bookworm.user.ProfileResponse;
import com.bookworm.user.Role;
import com.bookworm.user.UserService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    private UserService userService;
    private PasswordEncoder encoder;
    private JwtTokenService tokens;
    private LoginAttemptService attempts;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        encoder = mock(PasswordEncoder.class);
        tokens = mock(JwtTokenService.class);
        attempts = mock(LoginAttemptService.class);
        when(encoder.encode(anyString())).thenReturn("{bcrypt}hash");
        service = new AuthService(userService, encoder, tokens, attempts, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Credentials account(boolean enabled, Instant lockedUntil) {
        return new Credentials(7L, "{bcrypt}stored", Role.CUSTOMER, enabled, lockedUntil);
    }

    // ---- registration -----------------------------------------------------------------------------

    @Test
    void registrationNormalisesTheEmailHashesThePasswordAndNeverAcceptsARole() {
        ProfileResponse created = new ProfileResponse(1L, "reader@example.test", "Ada", "Reader", null, Role.CUSTOMER);
        when(userService.registerCustomer("reader@example.test", "{bcrypt}hash", "Ada", "Reader", null))
                .thenReturn(created);

        ProfileResponse result = service.register(new RegisterRequest("  Reader@Example.TEST ",
                "a long enough password", " Ada ", " Reader ", null));

        assertThat(result.role()).isEqualTo(Role.CUSTOMER);
        verify(encoder).encode("a long enough password");
    }

    @Test
    void registrationReportsEveryProblemTogether() {
        assertThatThrownBy(() -> service.register(new RegisterRequest("not-an-email", "short", "Ada", "Reader", null)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getFieldProblems()).extracting(ApiException.FieldProblem::field)
                            .containsExactlyInAnyOrder("email", "password");
                });
        verifyNoInteractions(userService);
    }

    // ---- login ------------------------------------------------------------------------------------

    @Test
    void unknownEmailStillRunsAPasswordCheckAndReturnsTheGenericError() {
        when(userService.findCredentialsByEmail("nobody@example.test")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("Nobody@example.test", "whatever password")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                    assertThat(ex.getMessage()).isEqualTo("Invalid email or password.");
                });
        verify(encoder).matches(any(), anyString()); // timing equalisation against a dummy hash
        verifyNoInteractions(attempts);
    }

    @Test
    void wrongPasswordRecordsAFailureAndUsesTheSameGenericError() {
        when(userService.findCredentialsByEmail("reader@example.test")).thenReturn(Optional.of(account(true, null)));
        when(encoder.matches("wrong password!", "{bcrypt}stored")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("reader@example.test", "wrong password!")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                    assertThat(ex.getMessage()).isEqualTo("Invalid email or password.");
                });
        verify(attempts).recordFailure(7L);
        verify(tokens, never()).issue(any(), any());
    }

    @Test
    void aLockedAccountIsRefusedEvenWithTheCorrectPasswordAndTheCounterDoesNotMove() {
        when(userService.findCredentialsByEmail("reader@example.test"))
                .thenReturn(Optional.of(account(true, NOW.plus(Duration.ofMinutes(5)))));

        assertThatThrownBy(() -> service.login(new LoginRequest("reader@example.test", "the right password")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_LOCKED));
        verifyNoInteractions(attempts);
        verify(encoder, never()).matches(any(), anyString());
    }

    @Test
    void anExpiredLockDoesNotBlockLogin() {
        when(userService.findCredentialsByEmail("reader@example.test"))
                .thenReturn(Optional.of(account(true, NOW.minusSeconds(1))));
        when(encoder.matches("the right password", "{bcrypt}stored")).thenReturn(true);
        when(tokens.issue(7L, Role.CUSTOMER)).thenReturn(new TokenResponse("t", "Bearer", 3600));

        TokenResponse response = service.login(new LoginRequest("reader@example.test", "the right password"));

        assertThat(response.tokenType()).isEqualTo("Bearer");
        verify(attempts).recordSuccess(7L);
    }

    @Test
    void aDisabledAccountCannotLogInEvenWithTheCorrectPassword() {
        when(userService.findCredentialsByEmail("reader@example.test")).thenReturn(Optional.of(account(false, null)));
        when(encoder.matches("the right password", "{bcrypt}stored")).thenReturn(true);

        assertThatThrownBy(() -> service.login(new LoginRequest("reader@example.test", "the right password")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                    assertThat(ex.getMessage()).isEqualTo("Invalid email or password.");
                });
        verify(tokens, never()).issue(any(), any());
    }

    @Test
    void aPasswordOverTheBcryptByteLimitIsRejectedWithoutCallingBcrypt() {
        when(userService.findCredentialsByEmail("reader@example.test")).thenReturn(Optional.of(account(true, null)));

        assertThatThrownBy(() -> service.login(new LoginRequest("reader@example.test", "a".repeat(100_000))))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        verify(encoder, never()).matches(any(), anyString());
        verify(attempts).recordFailure(7L);
    }

    // ---- password change --------------------------------------------------------------------------

    @Test
    void aWrongCurrentPasswordIsAFieldErrorAndCountsTowardsLockout() {
        when(userService.findCredentialsById(7L)).thenReturn(Optional.of(account(true, null)));
        when(encoder.matches("not my password", "{bcrypt}stored")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(7L, new ChangePasswordRequest("not my password",
                "a brand new password")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getFieldProblems()).extracting(ApiException.FieldProblem::field)
                            .containsExactly("currentPassword");
                });
        verify(attempts).recordFailure(7L);
        verify(userService, never()).changePasswordHash(any(), anyString());
    }

    @Test
    void theNewPasswordMustFollowThePolicyAndDifferFromTheCurrentOne() {
        when(userService.findCredentialsById(7L)).thenReturn(Optional.of(account(true, null)));
        when(encoder.matches("current password!", "{bcrypt}stored")).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(7L, new ChangePasswordRequest("current password!", "short")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.changePassword(7L,
                new ChangePasswordRequest("current password!", "current password!")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getFieldProblems()).extracting(ApiException.FieldProblem::message)
                                .containsExactly("must differ from the current password"));
        verify(userService, never()).changePasswordHash(any(), anyString());
    }

    @Test
    void aValidChangeStoresTheNewHashAndClearsFailures() {
        when(userService.findCredentialsById(7L)).thenReturn(Optional.of(account(true, null)));
        when(encoder.matches("current password!", "{bcrypt}stored")).thenReturn(true);

        service.changePassword(7L, new ChangePasswordRequest("current password!", "a brand new password"));

        verify(userService).changePasswordHash(7L, "{bcrypt}hash");
        verify(attempts).recordSuccess(7L);
    }

    @Test
    void aTokenForAMissingAccountCannotChangeAPassword() {
        when(userService.findCredentialsById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changePassword(7L, new ChangePasswordRequest("x".repeat(12), "y".repeat(12))))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    void requestRecordsNeverRevealSecretsInToString() {
        assertThat(new LoginRequest("a@example.test", "super secret value").toString())
                .doesNotContain("super secret value").doesNotContain("a@example.test");
        assertThat(new RegisterRequest("a@example.test", "super secret value", "A", "B", null).toString())
                .doesNotContain("super secret value");
        assertThat(new ChangePasswordRequest("old secret value", "new secret value").toString())
                .doesNotContain("old secret value").doesNotContain("new secret value");
        assertThat(new TokenResponse("eyJ.secret.token", "Bearer", 3600).toString())
                .doesNotContain("eyJ.secret.token");
        assertThat(account(true, null).toString()).doesNotContain("stored");
    }
}
