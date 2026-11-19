package com.bookworm.common.error;

/**
 * Business-rule failure. The message is returned to the client, so it must never contain
 * sensitive data, SQL, class names or other internals.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
