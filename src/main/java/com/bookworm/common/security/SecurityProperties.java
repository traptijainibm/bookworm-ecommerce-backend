package com.bookworm.common.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Security settings. Secrets (JWT key, bootstrap admin password) come from environment variables only and
 * their {@code toString()} is redacted so they cannot leak through logging of this object.
 */
@ConfigurationProperties(prefix = "bookworm.security")
public record SecurityProperties(
        @DefaultValue("12") int bcryptStrength,
        @DefaultValue Jwt jwt,
        @DefaultValue Lockout lockout,
        @DefaultValue Admin admin) {

    public record Jwt(String secret, @DefaultValue("bookworm") String issuer, @DefaultValue("60m") Duration ttl) {
        @Override
        public String toString() {
            return "Jwt[secret=<redacted>, issuer=" + issuer + ", ttl=" + ttl + "]";
        }
    }

    public record Lockout(@DefaultValue("5") int maxAttempts, @DefaultValue("15m") Duration duration) {
    }

    public record Admin(String email, String password) {
        public boolean emailSet() {
            return email != null && !email.isBlank();
        }

        public boolean passwordSet() {
            return password != null && !password.isBlank();
        }

        @Override
        public String toString() {
            return "Admin[email=<redacted>, password=<redacted>]";
        }
    }
}
