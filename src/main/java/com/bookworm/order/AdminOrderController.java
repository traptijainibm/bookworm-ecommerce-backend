package com.bookworm.order;

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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin order management endpoints (ADMIN only).
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/orders")
@Tag(name = "Admin orders", description = "Manage customer orders (ADMIN only)")
public class AdminOrderController {

    private static final String BEARER = "bearerAuth";

    private final CheckoutService checkoutService;

    public AdminOrderController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @Operation(summary = "Search and list customer orders",
            security = @SecurityRequirement(name = BEARER))
    @GetMapping
    public PageResponse<OrderResponse> getAdminOrders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(description = "id | orderNumber | status | subtotal | total | createdAt | updatedAt, optionally ,asc or ,desc")
            @RequestParam(defaultValue = "createdAt,desc") @Pattern(
                    regexp = "(id|orderNumber|status|subtotal|total|createdAt|updatedAt)(,(asc|desc))?",
                    message = "must be one of id, orderNumber, status, subtotal, total, createdAt, updatedAt, optionally followed by ,asc or ,desc") String sort) {
        String[] parts = sort.split(",");
        Sort.Direction direction = parts.length > 1 ? Sort.Direction.fromString(parts[1]) : Sort.Direction.DESC;
        return checkoutService.getAdminOrders(status, page, size, parts[0], direction);
    }

    @Operation(summary = "Get any order details by order number",
            security = @SecurityRequirement(name = BEARER))
    @GetMapping("/{orderNumber}")
    public OrderResponse getAdminOrderByNumber(
            @PathVariable String orderNumber) {
        return checkoutService.getAdminOrder(orderNumber);
    }

    @Operation(summary = "Update an order status (applies transition rules and side effects)",
            security = @SecurityRequirement(name = BEARER))
    @PatchMapping("/{orderNumber}/status")
    public OrderResponse updateAdminOrderStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String orderNumber,
            @Valid @RequestBody OrderStatusUpdateRequest request) {
        return checkoutService.updateAdminOrderStatus(jwt.getSubject(), orderNumber, request);
    }
}
