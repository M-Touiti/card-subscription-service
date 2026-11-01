package com.demo.cardsubscription.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * When a subscription attempt may be repeated: an exponential back-off between
 * failed attempts, and a lease after which a PENDING attempt is presumed dead.
 */
@Component
public class SubscriptionRetryPolicy {

    private final Duration pendingLease;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final int maxAttempts;
    private final int batchSize;

    public SubscriptionRetryPolicy(
            @Value("${card-subscription.retry.pending-lease:60s}") Duration pendingLease,
            @Value("${card-subscription.retry.initial-delay:30s}") Duration initialDelay,
            @Value("${card-subscription.retry.max-delay:30m}") Duration maxDelay,
            @Value("${card-subscription.retry.max-attempts:8}") int maxAttempts,
            @Value("${card-subscription.retry.batch-size:50}") int batchSize) {
        this.pendingLease = pendingLease;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
    }

    public LocalDateTime leaseUntil(LocalDateTime now) {
        return now.plus(pendingLease);
    }

    /** @return when to retry after this many attempts, or {@code null} once they are exhausted */
    public LocalDateTime nextRetryAt(LocalDateTime now, int attempts) {
        if (attempts >= maxAttempts) {
            return null;
        }
        Duration delay = initialDelay.multipliedBy(1L << Math.min(attempts - 1, 20));
        return now.plus(delay.compareTo(maxDelay) > 0 ? maxDelay : delay);
    }

    public int batchSize() {
        return batchSize;
    }
}
