package com.bookworm.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/** Login result. {@code toString} is redacted so the token cannot end up in logs. */
@Schema(name = "TokenResponse")
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

    @Override
    public String toString() {
        return "TokenResponse[accessToken=<redacted>, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
