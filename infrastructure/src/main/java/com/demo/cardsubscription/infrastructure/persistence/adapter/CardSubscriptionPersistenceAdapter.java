package com.demo.cardsubscription.infrastructure.persistence.adapter;

import com.demo.cardsubscription.application.port.out.ClaimCardSubscriptionPort;
import com.demo.cardsubscription.application.port.out.LoadCardSubscriptionPort;
import com.demo.cardsubscription.application.port.out.SaveCardSubscriptionPort;
import com.demo.cardsubscription.domain.model.CardSubscription;
import com.demo.cardsubscription.infrastructure.persistence.entity.CardSubscriptionEntity;
import com.demo.cardsubscription.infrastructure.persistence.repository.CardSubscriptionJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistence adapter implementing the save, load and claim outbound ports.
 * Translates between domain model and JPA entity using MapStruct.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CardSubscriptionPersistenceAdapter
        implements SaveCardSubscriptionPort, LoadCardSubscriptionPort, ClaimCardSubscriptionPort {

    private final CardSubscriptionJpaRepository repository;
    private final CardSubscriptionEntityMapper mapper;

    // ─── SaveCardSubscriptionPort ─────────────────────────────────────────────

    @Override
    public CardSubscription save(CardSubscription subscription) {
        CardSubscriptionEntity entity = mapper.toEntity(subscription);
        CardSubscriptionEntity saved = repository.save(entity);
        log.debug("Persisted subscription id={} status={}", saved.getId(), saved.getStatus());
        return mapper.toDomain(saved);
    }

    // ─── LoadCardSubscriptionPort ─────────────────────────────────────────────

    @Override
    public Optional<CardSubscription> findById(String id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<CardSubscription> findByCardId(String cardId) {
        return repository.findByCardId(cardId).map(mapper::toDomain);
    }

    @Override
    public List<CardSubscription> findDueForRetry(LocalDateTime now, int limit) {
        return repository.findByNextRetryAtLessThanEqualOrderByNextRetryAt(now, PageRequest.of(0, limit))
                .stream().map(mapper::toDomain).toList();
    }

    // ─── ClaimCardSubscriptionPort ────────────────────────────────────────────

    @Override
    public boolean insertIfAbsent(CardSubscription pending) {
        return repository.insertIfAbsent(pending.getId(), pending.getCardId(), pending.getUserId(),
                pending.getStatus().name(), pending.getRequestedAt(), pending.getAttempts(),
                pending.getNextRetryAt()) == 1;
    }

    @Override
    public boolean claimForRetry(String cardId, String userId, LocalDateTime now, LocalDateTime leaseUntil) {
        return repository.claimForRetry(cardId, userId, now, leaseUntil) == 1;
    }

    @Override
    public boolean claimDue(String id, LocalDateTime now, LocalDateTime leaseUntil) {
        return repository.claimDue(id, now, leaseUntil) == 1;
    }
}
