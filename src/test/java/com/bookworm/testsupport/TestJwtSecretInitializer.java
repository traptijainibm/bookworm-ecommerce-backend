package com.bookworm.testsupport;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Test configuration that keeps secrets out of the repository: the JWT signing secret is generated randomly
 * once per test JVM (so no environment variable is needed to run tests), and the bootstrap-admin variables are
 * forced empty so a developer's real {@code BOOKWORM_ADMIN_*} settings can never create an account in tests.
 * Tests that forge tokens read {@link #SECRET_BYTES}.
 */
public class TestJwtSecretInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final byte[] SECRET_BYTES = new byte[48];

    static {
        new SecureRandom().nextBytes(SECRET_BYTES);
    }

    public static String secretBase64() {
        return Base64.getEncoder().encodeToString(SECRET_BYTES);
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TestPropertyValues.of(
                "bookworm.security.jwt.secret=" + secretBase64(),
                "bookworm.security.admin.email=",
                "bookworm.security.admin.password=",
                "bookworm.security.bcrypt-strength=4").applyTo(context);
    }
}
