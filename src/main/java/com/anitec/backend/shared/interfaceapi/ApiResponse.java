package com.anitec.backend.shared.interfaceapi;

import com.anitec.backend.shared.domain.FieldError;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.List;

/**
 * Standardized response envelope used for success AND error responses
 * (spec section 8.1): { success, message, data, errorCode?, details?, timestamp }.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"success", "message", "data", "errorCode", "details", "timestamp"})
public record ApiResponse<T>(
        boolean success,
        String message,
        T data,
        String errorCode,
        List<FieldError> details,
        Instant timestamp) {

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data, null, null, Instant.now());
    }

    public static <T> ApiResponse<T> ok(T data) {
        return ok("Operación realizada con éxito", data);
    }

    public static ApiResponse<Void> ok(String message) {
        return new ApiResponse<>(true, message, null, null, null, Instant.now());
    }

    public static ApiResponse<Void> error(String message, String errorCode, List<FieldError> details) {
        return new ApiResponse<>(false, message, null, errorCode,
                details == null || details.isEmpty() ? null : details, Instant.now());
    }
}
