package com.bookworm.wishlist;

import com.bookworm.common.security.CurrentUser;
import com.bookworm.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own wishlist. CUSTOMER only (see SecurityConfig). The user id comes only from the validated token.
 */
@Validated
@RestController
@RequestMapping("/api/v1/me/wishlist")
@Tag(name = "Wishlist", description = "The authenticated customer's own wishlist (CUSTOMER only)")
public class WishlistController {

    private static final String BEARER = "bearerAuth";

    private final WishlistService service;

    public WishlistController(WishlistService service) {
        this.service = service;
    }

    @Operation(summary = "View my wishlist (paginated, sorted by addedAt desc by default)",
            security = @SecurityRequirement(name = BEARER))
    @GetMapping
    public PageResponse<WishlistItemResponse> get(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(description = "addedAt, optionally followed by ,asc or ,desc")
            @RequestParam(defaultValue = "addedAt,desc") @Pattern(
                    regexp = "addedAt(,(asc|desc))?",
                    message = "must be addedAt, optionally followed by ,asc or ,desc") String sort) {
        return service.get(CurrentUser.id(jwt), page, size, sort);
    }

    @Operation(summary = "Add a book to the wishlist (idempotent for active books; 201 when new, 200 when already present)",
            security = @SecurityRequirement(name = BEARER))
    @PostMapping("/items")
    public ResponseEntity<WishlistItemResponse> add(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody WishlistAddRequest request) {
        WishlistService.AddResult result = service.add(CurrentUser.id(jwt), request.bookId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.item());
    }

    @Operation(summary = "Remove a book from the wishlist (idempotent, 204 no content)",
            security = @SecurityRequirement(name = BEARER))
    @DeleteMapping("/items/{bookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) Long bookId) {
        service.remove(CurrentUser.id(jwt), bookId);
    }
}
