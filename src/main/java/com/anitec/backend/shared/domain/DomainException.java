package com.anitec.backend.shared.domain;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Base domain/application exception. Carries the HTTP status, a stable machine
 * readable error code (spec section 8.1 error catalog) and optional field details.
 * Factories keep call sites short and consistent.
 */
public class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<FieldError> details;

    protected DomainException(HttpStatus status, String errorCode, String message, List<FieldError> details) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public List<FieldError> details() {
        return details;
    }

    // ---------------------------------------------------------------- factories

    public static DomainException badRequest(String errorCode, String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, errorCode, message, null);
    }

    public static DomainException unauthorized(String errorCode, String message) {
        return new DomainException(HttpStatus.UNAUTHORIZED, errorCode, message, null);
    }

    public static DomainException forbidden(String message) {
        return new DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", message, null);
    }

    public static DomainException notFound(String message) {
        return new DomainException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, null);
    }

    public static DomainException conflict(String errorCode, String message) {
        return new DomainException(HttpStatus.CONFLICT, errorCode, message, null);
    }

    public static DomainException business(String errorCode, String message) {
        return new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message, null);
    }

    public static DomainException business(String errorCode, String message, List<FieldError> details) {
        return new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message, details);
    }

    public static DomainException tooManyRequests(String errorCode, String message) {
        return new DomainException(HttpStatus.TOO_MANY_REQUESTS, errorCode, message, null);
    }

    public static DomainException internal(String message) {
        return new DomainException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", message, null);
    }
}
