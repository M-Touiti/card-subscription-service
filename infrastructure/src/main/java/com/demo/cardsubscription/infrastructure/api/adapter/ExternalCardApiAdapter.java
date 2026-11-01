package com.demo.cardsubscription.infrastructure.api.adapter;

import com.demo.cardsubscription.application.port.out.CardApiPort;
import com.demo.cardsubscription.infrastructure.api.dto.SubscribeCardApiRequest;
import com.demo.cardsubscription.infrastructure.api.dto.SubscribeCardApiResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * Adapter that calls the external card-subscription API.
 * Implements the outbound port defined in the application layer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalCardApiAdapter implements CardApiPort {

    private final RestTemplate restTemplate;

    @Value("${external.card-api.base-url}")
    private String baseUrl;

    @Value("${external.card-api.subscribe-path:/api/v1/subscribe-new-card}")
    private String subscribePath;

    @Override
    @CircuitBreaker(name = "externalCardApi", fallbackMethod = "subscribeCardFallback")
    public boolean subscribeCard(String cardId, String userId) {
        String url = baseUrl + subscribePath;
        SubscribeCardApiRequest request = new SubscribeCardApiRequest(cardId, userId);

        log.info("Calling external card API: POST {} | cardId={}", url, cardId);
        try {
            ResponseEntity<SubscribeCardApiResponse> response =
                    restTemplate.postForEntity(url, request, SubscribeCardApiResponse.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                boolean success = response.getBody().isSuccess();
                log.info("External API response for cardId={}: success={}", cardId, success);
                return success;
            }

            log.warn("Unexpected response status {} for cardId={}", response.getStatusCode(), cardId);
            return false;

        } catch (HttpClientErrorException ex) {
            log.error("HTTP client error calling card API for cardId={}: status={} body={}",
                    cardId, ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
            throw ex;
        }
    }

    // Called by Resilience4j when the circuit is OPEN or the call failed
    boolean subscribeCardFallback(String cardId, String userId, Throwable t) {
        log.warn("External API call not completed for cardId={}: {}", cardId, t.getMessage());
        return false;
    }
}
