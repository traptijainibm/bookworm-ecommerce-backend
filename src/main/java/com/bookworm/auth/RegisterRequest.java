package com.bookworm.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration input. There is deliberately no role, id or enabled field: unknown JSON properties are ignored,
 * so a client cannot choose its own role (no mass assignment). Email format and the password policy are
 * checked in {@link AuthService} so every problem is reported together as field errors.
 */
public record RegisterRequest(
        @NotBlank @Size(max = 254) String email,
        @NotNull String password,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @Pattern(regexp = "[0-9]{10}", message = "must be 10 digits") String phone) {

    @Override
    public String toString() {
        return "RegisterRequest[email=<redacted>, password=<redacted>]";
    }
}
