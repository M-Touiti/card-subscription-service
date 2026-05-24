package com.demo.cardsubscription.exposition.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "Card subscription state")
public record CardSubscriptionResponse(
        @Schema(description = "Internal subscription ID") String id,
        @Schema(description = "Card identifier") String cardId,
        @Schema(description = "User identifier") String userId,
        @Schema(description = "Subscription status", allowableValues = {"PENDING", "SUBSCRIBED", "FAILED"}) String status,
        @Schema(description = "When the subscription was requested") LocalDateTime requestedAt,
        @Schema(description = "When the subscription was processed") LocalDateTime processedAt,
        @Schema(description = "Error message if status is FAILED") String errorMessage
) {
}
