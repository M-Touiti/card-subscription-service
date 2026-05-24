package com.demo.cardsubscription.exposition.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for subscribing a new card")
public record SubscribeCardRequest(

        @Schema(description = "Unique card identifier", example = "CARD-001", maxLength = 64)
        @NotBlank(message = "cardId must not be blank")
        @Size(max = 64, message = "cardId must not exceed 64 characters")
        String cardId,

        @Schema(description = "Unique user identifier", example = "USER-42", maxLength = 64)
        @NotBlank(message = "userId must not be blank")
        @Size(max = 64, message = "userId must not exceed 64 characters")
        String userId
) {
}
