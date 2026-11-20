package com.bookworm.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Stable, machine-readable error codes returned in the {@code code} property of every
 * {@link org.springframework.http.ProblemDetail}. Later phases add domain codes here.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "Account locked"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Not acceptable"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "Duplicate resource"),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "Request conflicts with existing data"),
    CONCURRENT_UPDATE(HttpStatus.CONFLICT, "Concurrent modification"),
    // Phase 4 (cart and wishlist).
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Insufficient stock"),
    BOOK_UNAVAILABLE(HttpStatus.CONFLICT, "Book unavailable"),
    QUANTITY_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Quantity limit exceeded"),
    CART_FULL(HttpStatus.UNPROCESSABLE_CONTENT, "Cart full"),
    WISHLIST_FULL(HttpStatus.UNPROCESSABLE_CONTENT, "Wishlist full"),
    // Checkout & Order codes
    PAYMENT_DECLINED(HttpStatus.PAYMENT_REQUIRED, "Payment declined"),
    PAYMENT_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "Payment gateway unavailable"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency key reused with different payload"),
    CART_EMPTY(HttpStatus.UNPROCESSABLE_CONTENT, "Cart empty"),
    POINTS_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Points limit exceeded"),
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "Invalid status transition"),
    REQUEST_FAILED(HttpStatus.BAD_REQUEST, "Request failed"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** Fallback code for errors raised by Spring MVC itself, derived from the HTTP status. */
    public static ErrorCode fromStatus(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 400 -> MALFORMED_REQUEST;
            case 401 -> UNAUTHENTICATED;
            case 402 -> PAYMENT_DECLINED;
            case 403 -> ACCESS_DENIED;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 502 -> PAYMENT_UNAVAILABLE;
            default -> statusCode.is5xxServerError() ? INTERNAL_ERROR : REQUEST_FAILED;
        };
    }
}
