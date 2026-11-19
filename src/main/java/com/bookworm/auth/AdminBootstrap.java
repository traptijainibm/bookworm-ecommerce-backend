package com.bookworm.auth;

import com.bookworm.common.security.Emails;
import com.bookworm.common.security.PasswordPolicy;
import com.bookworm.common.security.SecurityProperties;
import com.bookworm.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first administrator from {@code BOOKWORM_ADMIN_EMAIL} and {@code BOOKWORM_ADMIN_PASSWORD}.
 * <ul>
 *   <li>Neither set: nothing happens. There are no default credentials.</li>
 *   <li>Only one set, an invalid email, or a password that breaks the policy: startup fails. The message never
 *       contains the value.</li>
 *   <li>Insert-only ({@code ON CONFLICT DO NOTHING}): if ANY account already has that email, nothing is
 *       changed. An existing CUSTOMER is never promoted and an existing account's password is never
 *       overwritten, even if a different password is configured.</li>
 *   <li>Idempotent: running it on every startup leaves exactly one account.</li>
 *   <li>Credentials are never logged: only the outcome is.</li>
 * </ul>
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final SecurityProperties.Admin admin;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(SecurityProperties properties, UserService userService, PasswordEncoder passwordEncoder) {
        this.admin = properties.admin();
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!admin.emailSet() && !admin.passwordSet()) {
            log.info("Admin bootstrap skipped: no administrator credentials configured");
            return;
        }
        if (admin.emailSet() != admin.passwordSet()) {
            throw new IllegalStateException(
                    "BOOKWORM_ADMIN_EMAIL and BOOKWORM_ADMIN_PASSWORD must be set together or not at all");
        }
        String email = Emails.normalise(admin.email());
        if (!Emails.isValid(email)) {
            throw new IllegalStateException("BOOKWORM_ADMIN_EMAIL is not a valid email address");
        }
        String passwordProblem = PasswordPolicy.violation(admin.password(), email);
        if (passwordProblem != null) {
            throw new IllegalStateException("BOOKWORM_ADMIN_PASSWORD " + passwordProblem);
        }

        if (userService.emailExists(email)) {
            log.info("Admin bootstrap skipped: an account with the configured email already exists and was not modified");
            return;
        }
        boolean created = userService.createAdminIfAbsent(email, passwordEncoder.encode(admin.password()));
        log.info(created ? "Admin bootstrap created the administrator account"
                : "Admin bootstrap skipped: an account with the configured email already exists and was not modified");
    }
}
