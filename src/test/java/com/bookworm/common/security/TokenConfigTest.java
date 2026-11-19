package com.bookworm.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bookworm.auth.JwtTokenService;
import com.bookworm.auth.TokenResponse;
import com.bookworm.user.Role;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

class TokenConfigTest {

    private static String randomSecret(int bytes) {
        byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static SecurityProperties properties(String secret, Duration ttl) {
        return new SecurityProperties(4, new SecurityProperties.Jwt(secret, "bookworm", ttl),
                new SecurityProperties.Lockout(5, Duration.ofMinutes(15)), new SecurityProperties.Admin(null, null));
    }

    private final TokenConfig config = new TokenConfig();

    @Test
    void startupFailsWithoutRevealingTheValueWhenTheSecretIsMissingMalformedOrShort() {
        assertThatThrownBy(() -> config.jwtDecoder(properties(null, Duration.ofMinutes(60))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be set");
        assertThatThrownBy(() -> config.jwtDecoder(properties("   ", Duration.ofMinutes(60))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.jwtDecoder(properties("not base64 !!!", Duration.ofMinutes(60))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("base64")
                .hasMessageNotContaining("not base64 !!!");
        String shortSecret = randomSecret(31);
        assertThatThrownBy(() -> config.jwtDecoder(properties(shortSecret, Duration.ofMinutes(60))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 32 bytes")
                .hasMessageNotContaining(shortSecret);
        assertThatThrownBy(() -> config.jwtEncoder(properties(shortSecret, Duration.ofMinutes(60))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aThirtyTwoByteSecretIsAccepted() {
        assertThat(config.jwtDecoder(properties(randomSecret(32), Duration.ofMinutes(60)))).isNotNull();
    }

    @Test
    void issuedTokenCarriesTheDocumentedClaimsAndSixtyMinuteLifetime() {
        SecurityProperties props = properties(randomSecret(48), Duration.ofMinutes(60));
        JwtEncoder encoder = config.jwtEncoder(props);
        JwtDecoder decoder = config.jwtDecoder(props);
        Instant now = Instant.now().minusSeconds(5);
        JwtTokenService service = new JwtTokenService(encoder, Clock.fixed(now, ZoneOffset.UTC), props);

        TokenResponse response = service.issue(42L, Role.ADMIN);
        Jwt jwt = decoder.decode(response.accessToken());

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600L);
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("bookworm");
        assertThat(jwt.getIssuedAt()).isEqualTo(now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void anExpiredTokenIsRejectedWithNoClockSkew() {
        SecurityProperties props = properties(randomSecret(48), Duration.ofMinutes(60));
        JwtDecoder decoder = config.jwtDecoder(props);
        Instant past = Instant.now().minus(Duration.ofMinutes(61));
        String token = new JwtTokenService(config.jwtEncoder(props), Clock.fixed(past, ZoneOffset.UTC), props)
                .issue(1L, Role.CUSTOMER).accessToken();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRejected() {
        SecurityProperties mine = properties(randomSecret(48), Duration.ofMinutes(60));
        SecurityProperties theirs = properties(randomSecret(48), Duration.ofMinutes(60));
        String forged = new JwtTokenService(config.jwtEncoder(theirs), Clock.systemUTC(), theirs)
                .issue(1L, Role.ADMIN).accessToken();

        assertThatThrownBy(() -> config.jwtDecoder(mine).decode(forged))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }

    @Test
    void passwordEncoderProducesBcryptHashesWithTheConfiguredStrengthAndVerifiesThem() {
        PasswordEncoder encoder = config.passwordEncoder(properties(randomSecret(48), Duration.ofMinutes(60)));

        String hash = encoder.encode("correct horse battery");

        assertThat(hash).startsWith("{bcrypt}$2").contains("$04$");
        assertThat(encoder.matches("correct horse battery", hash)).isTrue();
        assertThat(encoder.matches("wrong horse battery", hash)).isFalse();
        assertThat(encoder.encode("correct horse battery")).isNotEqualTo(hash); // random salt
    }

    @Test
    void sensitivePropertiesAreRedactedInToString() {
        String secret = randomSecret(48);
        SecurityProperties props = new SecurityProperties(12, new SecurityProperties.Jwt(secret, "bookworm",
                Duration.ofMinutes(60)), new SecurityProperties.Lockout(5, Duration.ofMinutes(15)),
                new SecurityProperties.Admin("boss@example.test", "super secret value"));

        assertThat(props.toString()).doesNotContain(secret).doesNotContain("super secret value")
                .doesNotContain("boss@example.test");
    }
}
