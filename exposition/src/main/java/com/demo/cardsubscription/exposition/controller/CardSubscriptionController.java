package com.demo.cardsubscription.exposition.controller;

import com.demo.cardsubscription.application.command.SubscribeCardCommand;
import com.demo.cardsubscription.application.port.in.GetCardSubscriptionUseCase;
import com.demo.cardsubscription.application.port.in.SubscribeCardUseCase;
import com.demo.cardsubscription.domain.model.CardSubscription;
import com.demo.cardsubscription.exposition.converter.CardSubscriptionResponseConverter;
import com.demo.cardsubscription.exposition.converter.SubscribeCardRequestConverter;
import com.demo.cardsubscription.exposition.dto.request.SubscribeCardRequest;
import com.demo.cardsubscription.exposition.dto.response.CardSubscriptionResponse;
import com.demo.cardsubscription.exposition.exception.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/card-subscriptions")
@RequiredArgsConstructor
@Tag(name = "Card Subscriptions", description = "Card subscription lifecycle management")
public class CardSubscriptionController {

    private final SubscribeCardUseCase subscribeCardUseCase;
    private final GetCardSubscriptionUseCase getCardSubscriptionUseCase;
    private final SubscribeCardRequestConverter requestConverter;
    private final CardSubscriptionResponseConverter responseConverter;

    @Operation(summary = "Subscribe a new card")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Subscription created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = CardSubscriptionResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "500", description = "Internal server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping
    public ResponseEntity<CardSubscriptionResponse> subscribe(
            @RequestBody @Valid SubscribeCardRequest request) {

        log.info("POST /card-subscriptions | cardId={}", request.cardId());

        SubscribeCardCommand command = requestConverter.toCommand(request);
        CardSubscription subscription = subscribeCardUseCase.subscribe(command);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseConverter.toResponse(subscription));
    }

    @Operation(summary = "Get a subscription by internal ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Subscription found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = CardSubscriptionResponse.class))),
        @ApiResponse(responseCode = "404", description = "Subscription not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<CardSubscriptionResponse> getById(@PathVariable String id) {
        log.info("GET /card-subscriptions/{}", id);
        CardSubscription subscription = getCardSubscriptionUseCase.getById(id);
        return ResponseEntity.ok(responseConverter.toResponse(subscription));
    }

    @Operation(summary = "Get a subscription by card ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Subscription found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = CardSubscriptionResponse.class))),
        @ApiResponse(responseCode = "404", description = "Subscription not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/card/{cardId}")
    public ResponseEntity<CardSubscriptionResponse> getByCardId(@PathVariable String cardId) {
        log.info("GET /card-subscriptions/card/{}", cardId);
        CardSubscription subscription = getCardSubscriptionUseCase.getByCardId(cardId);
        return ResponseEntity.ok(responseConverter.toResponse(subscription));
    }
}
