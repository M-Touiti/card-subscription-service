package com.demo.cardsubscription.exposition.scheduling;

import com.demo.cardsubscription.application.port.in.RetrySubscriptionsUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically retries subscriptions that failed or were left PENDING by a crash.
 * Safe to run on every replica: each retry is claimed atomically.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "card-subscription.retry.enabled", havingValue = "true", matchIfMissing = true)
public class SubscriptionRetryJob {

    private final RetrySubscriptionsUseCase retrySubscriptionsUseCase;

    @Scheduled(fixedDelayString = "${card-subscription.retry.interval:PT5S}")
    public void retryDueSubscriptions() {
        int retried = retrySubscriptionsUseCase.retryDue();
        if (retried > 0) {
            log.info("Retried {} subscriptions", retried);
        }
    }
}
