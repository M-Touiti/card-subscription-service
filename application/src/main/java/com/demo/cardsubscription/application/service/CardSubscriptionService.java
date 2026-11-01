package com.demo.cardsubscription.application.service;

import com.demo.cardsubscription.application.command.SubscribeCardCommand;
import com.demo.cardsubscription.application.port.in.GetCardSubscriptionUseCase;
import com.demo.cardsubscription.application.port.in.RetrySubscriptionsUseCase;
import com.demo.cardsubscription.application.port.in.SubscribeCardUseCase;
import com.demo.cardsubscription.application.port.out.CardApiPort;
import com.demo.cardsubscription.application.port.out.ClaimCardSubscriptionPort;
import com.demo.cardsubscription.application.port.out.LoadCardSubscriptionPort;
import com.demo.cardsubscription.application.port.out.SaveCardSubscriptionPort;
import com.demo.cardsubscription.domain.exception.CardSubscriptionNotFoundException;
import com.demo.cardsubscription.domain.model.CardSubscription;
import com.demo.cardsubscription.domain.model.SubscriptionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service implementing all card-subscription use cases.
 * Orchestrates domain logic, outbound ports, and external API calls.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardSubscriptionService
        implements SubscribeCardUseCase, GetCardSubscriptionUseCase, RetrySubscriptionsUseCase {

    private final SaveCardSubscriptionPort saveCardSubscriptionPort;
    private final LoadCardSubscriptionPort loadCardSubscriptionPort;
    private final ClaimCardSubscriptionPort claimCardSubscriptionPort;
    private final CardApiPort cardApiPort;
    private final SubscriptionRetryPolicy retryPolicy;

    // ─── SubscribeCardUseCase ──────────────────────────────────────────────────

    /**
     * Deliberately not transactional: each port call commits on its own, so no database
     * connection is held during the external call. Claiming first keeps it idempotent.
     */
    @Override
    public CardSubscription subscribe(SubscribeCardCommand command) {
        log.info("Processing subscription for cardId={}, userId={}", command.cardId(), command.userId());

        Optional<CardSubscription> existing = loadCardSubscriptionPort.findByCardId(command.cardId());
        if (existing.isEmpty()) {
            CardSubscription pending = newPendingSubscription(command);
            if (claimCardSubscriptionPort.insertIfAbsent(pending)) {
                return callExternalApi(pending);
            }
            // Lost the race: another request or replica inserted this card first
            existing = loadCardSubscriptionPort.findByCardId(command.cardId());
        }

        CardSubscription current = existing.orElseThrow();
        if (current.isActive()) {
            log.info("Card {} is already subscribed – skipping", command.cardId());
            return current;
        }

        LocalDateTime now = LocalDateTime.now();
        if (!claimCardSubscriptionPort.claimForRetry(
                command.cardId(), command.userId(), now, retryPolicy.leaseUntil(now))) {
            log.info("Card {} is being processed elsewhere – returning current state", command.cardId());
            return current;
        }
        return callExternalApi(loadCardSubscriptionPort.findByCardId(command.cardId()).orElseThrow());
    }

    // ─── RetrySubscriptionsUseCase ────────────────────────────────────────────

    @Override
    public int retryDue() {
        LocalDateTime now = LocalDateTime.now();
        int retried = 0;
        for (CardSubscription due : loadCardSubscriptionPort.findDueForRetry(now, retryPolicy.batchSize())) {
            // Another replica may have selected the same row; only one claim succeeds
            if (claimCardSubscriptionPort.claimDue(due.getId(), now, retryPolicy.leaseUntil(now))) {
                loadCardSubscriptionPort.findById(due.getId()).ifPresent(this::callExternalApi);
                retried++;
            }
        }
        return retried;
    }

    private CardSubscription newPendingSubscription(SubscribeCardCommand command) {
        LocalDateTime now = LocalDateTime.now();
        return CardSubscription.builder()
                .id(UUID.randomUUID().toString())
                .cardId(command.cardId())
                .userId(command.userId())
                .status(SubscriptionStatus.PENDING)
                .requestedAt(now)
                .attempts(1)
                .nextRetryAt(retryPolicy.leaseUntil(now))
                .build();
    }

    private CardSubscription callExternalApi(CardSubscription subscription) {
        String cardId = subscription.getCardId();
        LocalDateTime retryAt = retryPolicy.nextRetryAt(LocalDateTime.now(), subscription.getAttempts());
        try {
            boolean success = cardApiPort.subscribeCard(cardId, subscription.getUserId());
            if (success) {
                subscription.markSubscribed();
                log.info("Card {} subscribed successfully", cardId);
            } else {
                subscription.markFailed("External API returned a non-success response", retryAt);
                log.warn("Card {} subscription failed – API returned failure", cardId);
            }
        } catch (Exception ex) {
            log.error("External API error for cardId={}: {}", cardId, ex.getMessage(), ex);
            subscription.markFailed(ex.getMessage(), retryAt);
        }

        if (subscription.getStatus() == SubscriptionStatus.FAILED && retryAt == null) {
            log.error("Card {} failed after {} attempts – no further retries", cardId, subscription.getAttempts());
        }
        return saveCardSubscriptionPort.save(subscription);
    }

    // ─── GetCardSubscriptionUseCase ───────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CardSubscription getById(String id) {
        return loadCardSubscriptionPort.findById(id)
                .orElseThrow(() -> CardSubscriptionNotFoundException.forId(id));
    }

    @Override
    @Transactional(readOnly = true)
    public CardSubscription getByCardId(String cardId) {
        return loadCardSubscriptionPort.findByCardId(cardId)
                .orElseThrow(() -> CardSubscriptionNotFoundException.forCardId(cardId));
    }
}
