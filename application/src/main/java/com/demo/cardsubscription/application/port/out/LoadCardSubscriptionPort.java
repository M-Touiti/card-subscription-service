package com.demo.cardsubscription.application.port.out;

import com.demo.cardsubscription.domain.model.CardSubscription;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port: load a card subscription from persistence.
 * Implemented by the infrastructure persistence adapter.
 */
public interface LoadCardSubscriptionPort {

    Optional<CardSubscription> findById(String id);

    Optional<CardSubscription> findByCardId(String cardId);

    /** Subscriptions whose retry time has passed, oldest first. */
    List<CardSubscription> findDueForRetry(LocalDateTime now, int limit);
}
