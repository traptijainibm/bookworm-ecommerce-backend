package com.bookworm.common.security;

import com.bookworm.user.AuthenticatedUser;
import com.bookworm.user.Role;
import com.bookworm.user.UserService;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Runs on EVERY authenticated request, after the signature, issuer and expiry were validated. It validates the
 * token's claims and then checks the database:
 * <ul>
 *   <li>the subject must be a positive number and the {@code role} claim a known role;</li>
 *   <li>the account must still exist and be enabled, otherwise the (otherwise valid) token is rejected with 401,
 *       so deleting or disabling a user takes effect on the very next request;</li>
 *   <li>the granted authority is the user's CURRENT database role, never the role claim, so a role change
 *       (promotion or demotion) also takes effect on the next request. The claim is informational only.</li>
 * </ul>
 * Cost: one primary-key lookup per authenticated request.
 */
@Component
public class CurrentUserJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserService userService;

    public CurrentUserJwtConverter(UserService userService) {
        this.userService = userService;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Long userId = parseSubject(jwt.getSubject());
        requireKnownRole(jwt.getClaimAsString("role"));

        AuthenticatedUser user = userService.findAuthenticatedUser(userId)
                .orElseThrow(() -> new InvalidBearerTokenException("The token does not identify an active user."));

        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())),
                String.valueOf(user.id()));
    }

    private static Long parseSubject(String subject) {
        try {
            long id = Long.parseLong(subject);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the rejection below
        }
        throw new InvalidBearerTokenException("The token subject is invalid.");
    }

    private static void requireKnownRole(String role) {
        if (role != null) {
            for (Role known : Role.values()) {
                if (known.name().equals(role)) {
                    return;
                }
            }
        }
        throw new InvalidBearerTokenException("The token role is invalid.");
    }
}
