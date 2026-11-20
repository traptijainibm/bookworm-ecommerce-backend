package com.bookworm.order;

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
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer order and checkout endpoints (CUSTOMER only).
 */
@Validated
@RestController
@RequestMapping("/api/v1/me/orders")
@Tag(name = "Orders", description = "The authenticated customer's orders and checkout (CUSTOMER only)")
public class OrderController {

    private static final String BEARER = "bearerAuth";

    private final CheckoutService checkoutService;

    public OrderController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @Operation(summary = "Checkout cart contents into an order",
            security = @SecurityRequirement(name = BEARER))
    @PostMapping
    public ResponseEntity<OrderResponse> checkout(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Unique client-generated idempotency key (1-64 alphanumeric chars)")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request) {
        Long userId = CurrentUser.id(jwt);
        String userEmail = jwt.getSubject();
        CheckoutService.CheckoutResult result = checkoutService.checkout(userId, userEmail, idempotencyKey, request);

        if (result.isReplay()) {
            return ResponseEntity.ok()
                    .header("Idempotent-Replay", "true")
                    .body(result.order());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.order());
    }

    @Operation(summary = "Get current customer's order history",
            security = @SecurityRequirement(name = BEARER))
    @GetMapping
    public PageResponse<OrderResponse> getCustomerOrderHistory(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @Parameter(description = "id | orderNumber | status | subtotal | total | createdAt | updatedAt, optionally ,asc or ,desc")
            @RequestParam(defaultValue = "createdAt,desc") @Pattern(
                    regexp = "(id|orderNumber|status|subtotal|total|createdAt|updatedAt)(,(asc|desc))?",
                    message = "must be one of id, orderNumber, status, subtotal, total, createdAt, updatedAt, optionally followed by ,asc or ,desc") String sort) {
        String[] parts = sort.split(",");
        Sort.Direction direction = parts.length > 1 ? Sort.Direction.fromString(parts[1]) : Sort.Direction.DESC;
        return checkoutService.getCustomerOrderHistory(CurrentUser.id(jwt), status, page, size, parts[0], direction);
    }

    @Operation(summary = "Get order details by order number",
            security = @SecurityRequirement(name = BEARER))
    @GetMapping("/{orderNumber}")
    public OrderResponse getOrderByNumber(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String orderNumber) {
        return checkoutService.getOrderByNumber(CurrentUser.id(jwt), orderNumber);
    }

    @Operation(summary = "Cancel a customer's order (PLACED only)",
            security = @SecurityRequirement(name = BEARER))
    @PostMapping("/{orderNumber}/cancel")
    public OrderResponse cancelOrder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String orderNumber) {
        return checkoutService.cancelCustomerOrder(CurrentUser.id(jwt), jwt.getSubject(), orderNumber);
    }
}
