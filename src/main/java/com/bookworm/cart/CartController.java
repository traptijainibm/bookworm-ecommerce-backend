package com.bookworm.cart;

import com.bookworm.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own cart. CUSTOMER only (see SecurityConfig). The user id comes only from the validated token
 * (never from the URL, query or body), so one customer can never address another customer's cart.
 */
@RestController
@RequestMapping("/api/v1/me/cart")
@Tag(name = "Cart", description = "The authenticated customer's own shopping cart (CUSTOMER only)")
public class CartController {

    private static final String BEARER = "bearerAuth";

    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @Operation(summary = "View my cart (prices are live; creates nothing)",
            security = @SecurityRequirement(name = BEARER))
    @GetMapping
    public CartResponse get(@AuthenticationPrincipal Jwt jwt) {
        return service.get(CurrentUser.id(jwt));
    }

    @Operation(summary = "Add a book; an existing line is increased by the quantity (not idempotent)",
            security = @SecurityRequirement(name = BEARER))
    @PostMapping("/items")
    public CartResponse add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CartAddRequest request) {
        return service.add(CurrentUser.id(jwt), request.bookId(), request.quantity());
    }

    @Operation(summary = "Set the quantity of an existing line (idempotent)",
            security = @SecurityRequirement(name = BEARER))
    @PutMapping("/items/{bookId}")
    public CartResponse setQuantity(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) Long bookId,
                                    @Valid @RequestBody CartQuantityRequest request) {
        return service.setQuantity(CurrentUser.id(jwt), bookId, request.quantity());
    }

    @Operation(summary = "Remove a line (idempotent; an absent line also succeeds) and return the cart",
            security = @SecurityRequirement(name = BEARER))
    @DeleteMapping("/items/{bookId}")
    public CartResponse remove(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) Long bookId) {
        return service.remove(CurrentUser.id(jwt), bookId);
    }

    @Operation(summary = "Remove every line (idempotent) and return the empty cart",
            security = @SecurityRequirement(name = BEARER))
    @DeleteMapping("/items")
    public CartResponse clear(@AuthenticationPrincipal Jwt jwt) {
        return service.clear(CurrentUser.id(jwt));
    }
}
