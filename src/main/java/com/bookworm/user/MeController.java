package com.bookworm.user;

import com.bookworm.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own profile. The user id comes only from the validated token's subject (never from the URL,
 * query or body), so one user can never address another user's data.
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Profile", description = "The authenticated user's own account")
public class MeController {

    private final UserService userService;

    public MeController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "View my profile", security = @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        return userService.getProfile(CurrentUser.id(jwt));
    }

    @Operation(summary = "Update my profile (first name, last name, phone)",
            security = @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth"))
    @PutMapping
    public ProfileResponse update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(CurrentUser.id(jwt), request);
    }
}
