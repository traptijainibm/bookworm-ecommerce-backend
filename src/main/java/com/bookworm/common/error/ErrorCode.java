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
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Not acceptable"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "Duplicate resource"),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "Request conflicts with existing data"),
    CONCURRENT_UPDATE(HttpStatus.CONFLICT, "Concurrent modification"),
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
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> statusCode.is5xxServerError() ? INTERNAL_ERROR : REQUEST_FAILED;
        };
    }
}
