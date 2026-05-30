package com.demo.cardsubscription.integration;

import com.demo.cardsubscription.application.port.out.CardApiPort;
import com.demo.cardsubscription.exposition.dto.request.SubscribeCardRequest;
import com.demo.cardsubscription.exposition.dto.response.CardSubscriptionResponse;
import com.demo.cardsubscription.exposition.exception.ApiError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Card Subscription REST API — integration")
class CardSubscriptionRestIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    private CardApiPort cardApiPort;

    @BeforeEach
    void stubApiSuccess() {
        when(cardApiPort.subscribeCard(any(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("POST — creates subscription with SUBSCRIBED status")
    void subscribe_returns201WithSubscribedStatus() {
        var request = new SubscribeCardRequest("CARD-REST-001", "USER-REST-001");

        ResponseEntity<CardSubscriptionResponse> response =
                restTemplate.postForEntity("/api/v1/card-subscriptions", request, CardSubscriptionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.cardId()).isEqualTo("CARD-REST-001");
        assertThat(body.userId()).isEqualTo("USER-REST-001");
        assertThat(body.status()).isEqualTo("SUBSCRIBED");
        assertThat(body.id()).isNotNull();
        assertThat(body.processedAt()).isNotNull();
    }

    @Test
    @DisplayName("GET /{id} — returns subscription by internal ID")
    void getById_returns200() {
        var created = restTemplate.postForEntity(
                "/api/v1/card-subscriptions",
                new SubscribeCardRequest("CARD-REST-002", "USER-REST-002"),
                CardSubscriptionResponse.class);
        String id = Objects.requireNonNull(created.getBody()).id();

        ResponseEntity<CardSubscriptionResponse> response =
                restTemplate.getForEntity("/api/v1/card-subscriptions/" + id, CardSubscriptionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Objects.requireNonNull(response.getBody()).id()).isEqualTo(id);
    }

    @Test
    @DisplayName("GET /card/{cardId} — returns subscription by card ID")
    void getByCardId_returns200() {
        restTemplate.postForEntity(
                "/api/v1/card-subscriptions",
                new SubscribeCardRequest("CARD-REST-003", "USER-REST-003"),
                CardSubscriptionResponse.class);

        ResponseEntity<CardSubscriptionResponse> response =
                restTemplate.getForEntity(
                        "/api/v1/card-subscriptions/card/CARD-REST-003", CardSubscriptionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Objects.requireNonNull(response.getBody()).cardId()).isEqualTo("CARD-REST-003");
    }

    @Test
    @DisplayName("POST — idempotent: second call with same cardId returns same record, API called once")
    void subscribe_isIdempotent() {
        var request = new SubscribeCardRequest("CARD-REST-004", "USER-REST-004");

        var first  = restTemplate.postForEntity("/api/v1/card-subscriptions", request, CardSubscriptionResponse.class);
        var second = restTemplate.postForEntity("/api/v1/card-subscriptions", request, CardSubscriptionResponse.class);

        assertThat(Objects.requireNonNull(first.getBody()).id())
                .isEqualTo(Objects.requireNonNull(second.getBody()).id());
        verify(cardApiPort, times(1)).subscribeCard("CARD-REST-004", "USER-REST-004");
    }

    @Test
    @DisplayName("POST — external API returns false → subscription marked FAILED")
    void subscribe_marksFailedWhenApiReturnsFalse() {
        when(cardApiPort.subscribeCard(any(), any())).thenReturn(false);

        ResponseEntity<CardSubscriptionResponse> response = restTemplate.postForEntity(
                "/api/v1/card-subscriptions",
                new SubscribeCardRequest("CARD-REST-005", "USER-REST-005"),
                CardSubscriptionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(Objects.requireNonNull(response.getBody()).status()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("POST — blank cardId returns 400 with field-level validation error")
    void subscribe_returns400WhenCardIdBlank() {
        ResponseEntity<ApiError> response = restTemplate.postForEntity(
                "/api/v1/card-subscriptions",
                new SubscribeCardRequest("", "USER-REST-006"),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(Objects.requireNonNull(response.getBody()).details()).isNotEmpty();
    }

    @Test
    @DisplayName("GET /{id} — unknown ID returns 404")
    void getById_returns404WhenNotFound() {
        ResponseEntity<ApiError> response =
                restTemplate.getForEntity("/api/v1/card-subscriptions/does-not-exist", ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
