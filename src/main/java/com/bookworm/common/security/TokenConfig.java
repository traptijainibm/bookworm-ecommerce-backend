package com.bookworm.common.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Password hashing and JWT signing/validation beans. HS256 is pinned; the key comes from the environment. */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class TokenConfig {

    static final int MIN_SECRET_BYTES = 32;

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** {@code {bcrypt}} prefixed hashes with an explicit work factor (default 12). */
    @Bean
    public PasswordEncoder passwordEncoder(SecurityProperties properties) {
        return new DelegatingPasswordEncoder("bcrypt",
                Map.of("bcrypt", new BCryptPasswordEncoder(properties.bcryptStrength())));
    }

    @Bean
    public JwtEncoder jwtEncoder(SecurityProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    /**
     * Pins HS256 (so {@code alg=none} and algorithm-confusion tokens are rejected) and validates signature,
     * issuer and expiry with no clock skew. A token must carry {@code sub}, {@code iat} and {@code exp}.
     */
    @Bean
    public JwtDecoder jwtDecoder(SecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> requiredClaims = jwt ->
                (jwt.getSubject() == null || jwt.getExpiresAt() == null || jwt.getIssuedAt() == null)
                        ? OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "Missing required claim", null))
                        : OAuth2TokenValidatorResult.success();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(properties.jwt().issuer()),
                requiredClaims));
        return decoder;
    }

    /** Fails startup, without echoing the value, when the secret is missing, not base64 or too short. */
    static SecretKey secretKey(SecurityProperties properties) {
        String encoded = properties.jwt().secret();
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("bookworm.security.jwt.secret (BOOKWORM_JWT_SECRET) must be set");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("bookworm.security.jwt.secret must be base64 encoded");
        }
        if (key.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "bookworm.security.jwt.secret must decode to at least " + MIN_SECRET_BYTES + " bytes");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }
}
