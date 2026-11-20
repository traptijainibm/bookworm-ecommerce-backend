package com.bookworm.wishlist;

import com.bookworm.cart.CartResponse;
import com.bookworm.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Move items between shopping cart and wishlist (CUSTOMER only).
 * Both operations return the updated CartResponse.
 */
@Validated
@RestController
@Tag(name = "Cart & Wishlist Moves", description = "Move items between cart and wishlist (CUSTOMER only)")
public class WishlistMoveController {

    private static final String BEARER = "bearerAuth";

    private final WishlistMoveService service;

    public WishlistMoveController(WishlistMoveService service) {
        this.service = service;
    }

    @Operation(summary = "Move a wishlist item into the cart (quantity 1; leaves existing cart quantity unchanged)",
            security = @SecurityRequirement(name = BEARER))
    @PostMapping("/api/v1/me/wishlist/items/{bookId}/move-to-cart")
    public CartResponse moveToCart(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) Long bookId) {
        return service.moveToCart(CurrentUser.id(jwt), bookId);
    }

    @Operation(summary = "Move a cart line into the wishlist (discards line quantity; idempotent on wishlist side)",
            security = @SecurityRequirement(name = BEARER))
    @PostMapping("/api/v1/me/cart/items/{bookId}/move-to-wishlist")
    public CartResponse moveToWishlist(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) Long bookId) {
        return service.moveToWishlist(CurrentUser.id(jwt), bookId);
    }
}
