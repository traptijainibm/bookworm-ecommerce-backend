package com.bookworm.common.error;

import java.util.List;

/**
 * Business-rule failure. The message is returned to the client, so it must never contain
 * sensitive data, SQL, class names or other internals.
 */
public class ApiException extends RuntimeException {

    /** A field-level problem returned to the client with {@code VALIDATION_FAILED}. */
    public record FieldProblem(String field, String message) {
    }

    private final ErrorCode errorCode;
    private final List<FieldProblem> fieldProblems;

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public ApiException(ErrorCode errorCode, String message, List<FieldProblem> fieldProblems) {
        super(message);
        this.errorCode = errorCode;
        this.fieldProblems = List.copyOf(fieldProblems);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<FieldProblem> getFieldProblems() {
        return fieldProblems;
    }
}
