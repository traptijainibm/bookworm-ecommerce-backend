package com.bookworm.common.error;

import com.bookworm.common.web.TraceIdFilter;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single place that turns every failure into an RFC 9457 {@link ProblemDetail} carrying a stable
 * {@code code} and the request {@code traceId}. Internal details are logged server-side only.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record FieldViolation(String field, String message) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
        ErrorCode code = ex.getErrorCode();
        log.warn("Request rejected: code={}", code);
        if (!ex.getFieldProblems().isEmpty()) {
            List<FieldViolation> violations = ex.getFieldProblems().stream()
                    .map(p -> new FieldViolation(p.field(), p.message()))
                    .toList();
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), ex.getMessage());
            problem.setTitle(code.title());
            problem.setProperty("code", code.name());
            problem.setProperty("errors", violations);
            return handleExceptionInternal(ex, problem, new HttpHeaders(), code.status(), request);
        }
        return problem(ex, code, ex.getMessage(), request);
    }

    /** Without this the catch-all below would turn an authorization failure raised in a controller into a 500. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        log.warn("Access denied");
        return problem(ex, ErrorCode.ACCESS_DENIED, "You do not have permission to perform this action.", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
        log.warn("Authentication required");
        return problem(ex, ErrorCode.UNAUTHENTICATED, "Authentication is required to access this resource.", request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex, WebRequest request) {
        log.warn("Concurrent modification detected", ex);
        return problem(ex, ErrorCode.CONCURRENT_UPDATE,
                "The resource was modified by another request. Please retry.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, WebRequest request) {
        log.warn("Data integrity violation", ex);
        return problem(ex, ErrorCode.DATA_INTEGRITY_VIOLATION,
                "The request conflicts with existing data.", request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return validationFailed(ex, violations, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return problem(ex, ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> violations.add(new FieldViolation(e.getField(), e.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors()
                .forEach(e -> violations.add(new FieldViolation(e.getObjectName(), e.getDefaultMessage())));
        return validationFailed(ex, violations, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors()
                    .forEach(e -> violations.add(new FieldViolation(name, e.getDefaultMessage())));
        });
        return validationFailed(ex, violations, request);
    }

    /** Adds {@code code} (when absent) and {@code traceId} to every problem response, including Spring's own. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
                problem.setProperty("code", ErrorCode.fromStatus(statusCode).name());
            }
            String traceId = MDC.get(TraceIdFilter.MDC_KEY);
            if (traceId != null) {
                problem.setProperty("traceId", traceId);
            }
        }
        return response;
    }

    private ResponseEntity<Object> validationFailed(Exception ex, List<FieldViolation> violations,
            WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                ErrorCode.VALIDATION_FAILED.status(), "Request validation failed.");
        problem.setTitle(ErrorCode.VALIDATION_FAILED.title());
        problem.setProperty("code", ErrorCode.VALIDATION_FAILED.name());
        problem.setProperty("errors", violations);
        return handleExceptionInternal(ex, problem, new HttpHeaders(), ErrorCode.VALIDATION_FAILED.status(), request);
    }

    private ResponseEntity<Object> problem(Exception ex, ErrorCode code, String detail, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        return handleExceptionInternal(ex, problem, new HttpHeaders(), code.status(), request);
    }
}
