package com.bookworm.auth;

import com.bookworm.common.security.SecurityProperties;
import com.bookworm.user.UserService;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Failed-login bookkeeping (default: 5 consecutive failures lock the account for 15 minutes).
 * <p>
 * Both methods run in {@code REQUIRES_NEW}: the failure must be committed even though the request that
 * caused it ends in an error response, otherwise the counter would never advance. The increment itself is a
 * single atomic SQL statement (see {@code UserRepository#registerFailedLogin}), so parallel wrong guesses
 * cannot lose counts.
 * <p>
 * Limitations (documented in docs/DESIGN.md section 7): the lock is per account, so an attacker can lock a
 * victim out on purpose; there is no per-IP rate limit, CAPTCHA or breached-password check; and a locked
 * response reveals that the account exists.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final UserService userService;
    private final Clock clock;
    private final SecurityProperties.Lockout lockout;

    public LoginAttemptService(UserService userService, Clock clock, SecurityProperties properties) {
        this.userService = userService;
        this.clock = clock;
        this.lockout = properties.lockout();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long userId) {
        Instant now = clock.instant();
        userService.registerFailedLogin(userId, now, lockout.maxAttempts(), now.plus(lockout.duration()));
        userService.findCredentialsById(userId)
                .filter(credentials -> credentials.lockedAt(now))
                .ifPresent(credentials -> log.warn("Account locked after repeated failed logins: userId={}", userId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long userId) {
        userService.clearLoginFailures(userId);
    }
}
