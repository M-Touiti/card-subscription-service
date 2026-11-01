package com.demo.cardsubscription.infrastructure.persistence.repository;

import com.demo.cardsubscription.infrastructure.persistence.entity.CardSubscriptionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CardSubscriptionJpaRepository extends JpaRepository<CardSubscriptionEntity, String> {

    Optional<CardSubscriptionEntity> findByCardId(String cardId);

    List<CardSubscriptionEntity> findByNextRetryAtLessThanEqualOrderByNextRetryAt(LocalDateTime now, Pageable page);

    // ON CONFLICT rather than catching a constraint violation: losing the race is expected
    // under concurrency, and should not cost an aborted transaction and an error log line.
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO card_subscription (id, card_id, user_id, status, requested_at, attempts, next_retry_at)
            VALUES (:id, :cardId, :userId, :status, :requestedAt, :attempts, :nextRetryAt)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") String id,
                       @Param("cardId") String cardId,
                       @Param("userId") String userId,
                       @Param("status") String status,
                       @Param("requestedAt") LocalDateTime requestedAt,
                       @Param("attempts") int attempts,
                       @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Transactional
    @Modifying
    @Query("""
            UPDATE CardSubscriptionEntity s
            SET s.status = 'PENDING', s.userId = :userId, s.attempts = s.attempts + 1,
                s.errorMessage = null, s.processedAt = null, s.nextRetryAt = :leaseUntil
            WHERE s.cardId = :cardId
              AND (s.status = 'FAILED' OR (s.status = 'PENDING' AND s.nextRetryAt <= :now))
            """)
    int claimForRetry(@Param("cardId") String cardId,
                      @Param("userId") String userId,
                      @Param("now") LocalDateTime now,
                      @Param("leaseUntil") LocalDateTime leaseUntil);

    @Transactional
    @Modifying
    @Query("""
            UPDATE CardSubscriptionEntity s
            SET s.status = 'PENDING', s.attempts = s.attempts + 1,
                s.errorMessage = null, s.processedAt = null, s.nextRetryAt = :leaseUntil
            WHERE s.id = :id AND s.nextRetryAt <= :now
            """)
    int claimDue(@Param("id") String id,
                 @Param("now") LocalDateTime now,
                 @Param("leaseUntil") LocalDateTime leaseUntil);
}
