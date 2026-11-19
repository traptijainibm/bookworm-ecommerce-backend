package com.bookworm.user;

/** The result of the per-request current-user check: an existing, enabled account and its CURRENT role. */
public record AuthenticatedUser(Long id, Role role) {
}
