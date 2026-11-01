package com.demo.cardsubscription.integration;

import com.demo.cardsubscription.application.port.in.RetrySubscriptionsUseCase;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Card Subscription REST API — integration")
class CardSubscriptionRestIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RetrySubscriptionsUseCase retrySubscriptionsUseCase;

    @MockBean
    private CardApiPort cardApiPort;

    private int rowsFor(String cardId) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM card_subscription WHERE card_id = ?", Integer.class, cardId));
    }

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

    @Test
    @DisplayName("POST — concurrent requests for one card create one row and call the API once")
    void subscribe_concurrentRequests_createOneRow() throws Exception {
        when(cardApiPort.subscribeCard(any(), any())).thenAnswer(inv -> {
            Thread.sleep(300); // keeps the first call in flight while the others arrive
            return true;
        });
        var request = new SubscribeCardRequest("CARD-REST-RACE", "USER-REST-RACE");

        List<ResponseEntity<CardSubscriptionResponse>> responses;
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<CompletableFuture<ResponseEntity<CardSubscriptionResponse>>> calls = IntStream.range(0, 8)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> restTemplate.postForEntity(
                            "/api/v1/card-subscriptions", request, CardSubscriptionResponse.class), pool))
                    .toList();
            responses = calls.stream().map(CompletableFuture::join).toList();
        }

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(responses).extracting(r -> Objects.requireNonNull(r.getBody()).id()).containsOnly(
                Objects.requireNonNull(responses.get(0).getBody()).id());
        assertThat(rowsFor("CARD-REST-RACE")).isEqualTo(1);
        verify(cardApiPort, times(1)).subscribeCard("CARD-REST-RACE", "USER-REST-RACE");
    }

    @Test
    @DisplayName("POST — retry after failure updates the same row, and later requests still work")
    void subscribe_retryAfterFailure_keepsOneRow() {
        var request = new SubscribeCardRequest("CARD-REST-RETRY", "USER-REST-RETRY");
        when(cardApiPort.subscribeCard(any(), any())).thenReturn(false);
        var failed = restTemplate.postForEntity("/api/v1/card-subscriptions", request, CardSubscriptionResponse.class);
        assertThat(Objects.requireNonNull(failed.getBody()).status()).isEqualTo("FAILED");

        when(cardApiPort.subscribeCard(any(), any())).thenReturn(true);
        var retried = restTemplate.postForEntity("/api/v1/card-subscriptions", request, CardSubscriptionResponse.class);
        var third = restTemplate.postForEntity("/api/v1/card-subscriptions", request, CardSubscriptionResponse.class);
        var lookup = restTemplate.getForEntity(
                "/api/v1/card-subscriptions/card/CARD-REST-RETRY", CardSubscriptionResponse.class);

        assertThat(Objects.requireNonNull(retried.getBody()).status()).isEqualTo("SUBSCRIBED");
        assertThat(retried.getBody().id()).isEqualTo(failed.getBody().id());
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lookup.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rowsFor("CARD-REST-RETRY")).isEqualTo(1);
    }

    @Test
    @DisplayName("POST — no transaction, and so no database connection, is held during the external call")
    void subscribe_holdsNoTransactionDuringExternalCall() {
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        when(cardApiPort.subscribeCard(any(), any())).thenAnswer(inv -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        });

        restTemplate.postForEntity("/api/v1/card-subscriptions",
                new SubscribeCardRequest("CARD-REST-TX", "USER-REST-TX"), CardSubscriptionResponse.class);

        assertThat(transactionActive).isFalse();
    }

    @Test
    @DisplayName("Retry job — a FAILED subscription is retried once its retry time passes")
    void retryDue_recoversFailedSubscription() {
        var request = new SubscribeCardRequest("CARD-REST-JOB", "USER-REST-JOB");
        when(cardApiPort.subscribeCard(any(), any())).thenReturn(false);
        restTemplate.postForEntity("/api/v1/card-subscriptions", request, CardSubscriptionResponse.class);

        retrySubscriptionsUseCase.retryDue();
        verify(cardApiPort, times(1)).subscribeCard("CARD-REST-JOB", "USER-REST-JOB"); // not due yet

        jdbcTemplate.update("UPDATE card_subscription SET next_retry_at = now() - interval '1 second' "
                + "WHERE card_id = 'CARD-REST-JOB'");
        when(cardApiPort.subscribeCard(any(), any())).thenReturn(true);
        retrySubscriptionsUseCase.retryDue();

        var lookup = restTemplate.getForEntity(
                "/api/v1/card-subscriptions/card/CARD-REST-JOB", CardSubscriptionResponse.class);
        assertThat(Objects.requireNonNull(lookup.getBody()).status()).isEqualTo("SUBSCRIBED");
    }

    @Test
    @DisplayName("Retry job — a PENDING subscription abandoned by a crash is completed after its lease expires")
    void retryDue_recoversAbandonedPending() {
        jdbcTemplate.update("INSERT INTO card_subscription "
                + "(id, card_id, user_id, status, requested_at, attempts, next_retry_at) "
                + "VALUES ('crashed-id', 'CARD-REST-CRASH', 'USER-REST-CRASH', 'PENDING', now(), 1, "
                + "now() - interval '1 second')");

        retrySubscriptionsUseCase.retryDue();

        var lookup = restTemplate.getForEntity(
                "/api/v1/card-subscriptions/card/CARD-REST-CRASH", CardSubscriptionResponse.class);
        assertThat(Objects.requireNonNull(lookup.getBody()).status()).isEqualTo("SUBSCRIBED");
    }

    @Test
    @DisplayName("GET /actuator/health — includes the circuit breaker state")
    void health_includesCircuitBreaker() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("circuitBreakers").contains("externalCardApi");
    }
}
