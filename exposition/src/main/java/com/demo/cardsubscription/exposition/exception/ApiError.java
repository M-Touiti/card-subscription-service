package com.demo.cardsubscription.exposition.exception;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Structured error response")
public record ApiError(
        @Schema(description = "HTTP status code", example = "404") int status,
        @Schema(description = "HTTP error phrase", example = "Not Found") String error,
        @Schema(description = "Human-readable message") String message,
        @Schema(description = "Timestamp of the error") LocalDateTime timestamp,
        @Schema(description = "Field-level validation details") List<String> details
) {
    public static ApiError of(int status, String error, String message) {
        return new ApiError(status, error, message, LocalDateTime.now(), List.of());
    }

    public static ApiError of(int status, String error, String message, List<String> details) {
        return new ApiError(status, error, message, LocalDateTime.now(), details);
    }
}
