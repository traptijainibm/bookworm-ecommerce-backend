package com.bookworm.common.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The only place that turns the validated token into a user id. {@code /me} endpoints must call this and
 * never read an id from the URL, query or body (prevents insecure direct object references).
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The subject is validated as a positive number by {@link CurrentUserJwtConverter} before a request is authenticated. */
    public static Long id(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
