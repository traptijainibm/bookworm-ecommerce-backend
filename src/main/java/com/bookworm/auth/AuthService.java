package com.bookworm.auth;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import com.bookworm.common.security.Emails;
import com.bookworm.common.security.PasswordPolicy;
import com.bookworm.user.Credentials;
import com.bookworm.user.ProfileResponse;
import com.bookworm.user.UserService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Registration, login and password change. Deliberately NOT {@code @Transactional}: the lockout writes run in
 * their own {@code REQUIRES_NEW} transactions and must commit even when this flow ends in an error.
 * Nothing here logs an email, password, hash or token.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid email or password.";

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final LoginAttemptService loginAttempts;
    private final Clock clock;

    /** Verified against when the email is unknown, so that response time does not reveal whether it exists. */
    private final String dummyHash;

    public AuthService(UserService userService, PasswordEncoder passwordEncoder, JwtTokenService tokenService,
                       LoginAttemptService loginAttempts, Clock clock) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.loginAttempts = loginAttempts;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-only");
    }

    public ProfileResponse register(RegisterRequest request) {
        String email = Emails.normalise(request.email());
        List<ApiException.FieldProblem> problems = new ArrayList<>();
        if (!Emails.isValid(email)) {
            problems.add(new ApiException.FieldProblem("email", "must be a valid email address"));
        }
        String passwordProblem = PasswordPolicy.violation(request.password(), email);
        if (passwordProblem != null) {
            problems.add(new ApiException.FieldProblem("password", passwordProblem));
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.", problems);
        }

        ProfileResponse profile = userService.registerCustomer(email, passwordEncoder.encode(request.password()),
                request.firstName().trim(), request.lastName().trim(), request.phone());
        log.info("User registered: userId={}", profile.id());
        return profile;
    }

    public TokenResponse login(LoginRequest request) {
        String email = Emails.normalise(request.email());
        Optional<Credentials> found = userService.findCredentialsByEmail(email);

        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyHash); // equalise timing for unknown emails
            log.warn("Login failed: reason=unknown_account");
            throw invalidCredentials();
        }

        Credentials user = found.get();
        if (user.lockedAt(clock.instant())) {
            // The counter does not move while locked, and even the correct password is refused.
            log.warn("Login refused: reason=account_locked userId={}", user.id());
            throw new ApiException(ErrorCode.ACCOUNT_LOCKED,
                    "This account is temporarily locked. Please try again later.");
        }

        boolean passwordMatches = !PasswordPolicy.exceedsBcryptLimit(request.password())
                && passwordEncoder.matches(request.password(), user.passwordHash());
        if (!passwordMatches || !user.enabled()) {
            if (user.enabled()) {
                loginAttempts.recordFailure(user.id());
            }
            log.warn("Login failed: reason={} userId={}", user.enabled() ? "bad_password" : "disabled", user.id());
            throw invalidCredentials();
        }

        loginAttempts.recordSuccess(user.id());
        log.info("Login succeeded: userId={}", user.id());
        return tokenService.issue(user.id(), user.role());
    }

    /**
     * Changes the caller's password. A wrong current password is a 400 field error and counts toward the same
     * lockout counter, so a stolen token cannot be used to guess the password. Existing tokens are NOT revoked.
     */
    public void changePassword(Long userId, ChangePasswordRequest request) {
        Credentials user = userService.findCredentialsById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required."));

        if (user.lockedAt(clock.instant())) {
            throw new ApiException(ErrorCode.ACCOUNT_LOCKED,
                    "This account is temporarily locked. Please try again later.");
        }

        boolean currentMatches = !PasswordPolicy.exceedsBcryptLimit(request.currentPassword())
                && passwordEncoder.matches(request.currentPassword(), user.passwordHash());
        if (!currentMatches) {
            loginAttempts.recordFailure(userId);
            log.warn("Password change refused: reason=bad_current_password userId={}", userId);
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                    List.of(new ApiException.FieldProblem("currentPassword", "is incorrect")));
        }

        PasswordPolicy.require("newPassword", request.newPassword(), null);
        if (request.newPassword().equals(request.currentPassword())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                    List.of(new ApiException.FieldProblem("newPassword", "must differ from the current password")));
        }

        userService.changePasswordHash(userId, passwordEncoder.encode(request.newPassword()));
        loginAttempts.recordSuccess(userId);
        log.info("Password changed: userId={}", userId);
    }

    private static ApiException invalidCredentials() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
    }
}
