package com.bookworm.user;

import io.swagger.v3.oas.annotations.media.Schema;

/** Public view of an account. Never carries the password hash, lockout fields or the version. */
@Schema(name = "Profile")
public record ProfileResponse(
        Long id,
        String email,
        String firstName,
        String lastName,
        String phone,
        Role role) {

    static ProfileResponse from(User user) {
        return new ProfileResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                user.getPhone(), user.getRole());
    }
}
