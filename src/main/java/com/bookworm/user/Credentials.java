package com.bookworm.user;

import java.time.Instant;

/** What the login and password-change flows need from an account. The hash must never be logged or returned. */
public record Credentials(Long id, String passwordHash, Role role, boolean enabled, Instant lockedUntil) {

    public boolean lockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    @Override
    public String toString() {
        return "Credentials[id=" + id + ", role=" + role + ", enabled=" + enabled + "]";
    }
}
