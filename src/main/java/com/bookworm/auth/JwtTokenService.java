package com.bookworm.auth;

import com.bookworm.common.security.SecurityProperties;
import com.bookworm.user.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues HS256 access tokens with the claims {@code sub} (user id), {@code role}, {@code iss}, {@code iat}
 * and {@code exp}. There are no refresh tokens. The {@code role} claim is informational: authorization uses
 * the user's current database role (see {@code CurrentUserJwtConverter}).
 */
@Service
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final Clock clock;
    private final String issuer;
    private final Duration ttl;

    public JwtTokenService(JwtEncoder jwtEncoder, Clock clock, SecurityProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.clock = clock;
        this.issuer = properties.jwt().issuer();
        this.ttl = properties.jwt().ttl();
    }

    public TokenResponse issue(Long userId, Role role) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("role", role.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", ttl.toSeconds());
    }
}
