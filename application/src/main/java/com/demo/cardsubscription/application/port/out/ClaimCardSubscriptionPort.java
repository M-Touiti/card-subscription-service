package com.demo.cardsubscription.application.port.out;

import com.demo.cardsubscription.domain.model.CardSubscription;

import java.time.LocalDateTime;

/**
 * Outbound port: atomically take ownership of a subscription attempt.
 * Each method succeeds for exactly one caller across all replicas, so the external
 * API is called once per attempt however many requests or events arrive together.
 * Implemented by the infrastructure persistence adapter.
 */
public interface ClaimCardSubscriptionPort {

    /** Inserts the PENDING subscription unless the card already has one. */
    boolean insertIfAbsent(CardSubscription pending);

    /** Moves a FAILED subscription, or a PENDING one whose lease has expired, back to PENDING. */
    boolean claimForRetry(String cardId, String userId, LocalDateTime now, LocalDateTime leaseUntil);

    /** Moves a subscription whose retry is due back to PENDING. */
    boolean claimDue(String id, LocalDateTime now, LocalDateTime leaseUntil);
}
