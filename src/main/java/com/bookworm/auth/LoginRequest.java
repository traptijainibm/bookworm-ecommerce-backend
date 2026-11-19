package com.bookworm.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=<redacted>, password=<redacted>]";
    }
}
