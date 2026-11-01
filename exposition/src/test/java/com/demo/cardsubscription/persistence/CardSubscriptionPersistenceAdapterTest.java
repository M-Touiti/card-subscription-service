package com.demo.cardsubscription.persistence;

import com.demo.cardsubscription.domain.model.CardSubscription;
import com.demo.cardsubscription.domain.model.SubscriptionStatus;
import com.demo.cardsubscription.infrastructure.persistence.adapter.CardSubscriptionEntityMapperImpl;
import com.demo.cardsubscription.infrastructure.persistence.adapter.CardSubscriptionPersistenceAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the claim queries against the H2 database used for local development, in its
 * PostgreSQL mode. CardSubscriptionRestIT covers the same flow on real PostgreSQL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CardSubscriptionPersistenceAdapter.class, CardSubscriptionEntityMapperImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CardSubscriptionPersistenceAdapterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0);

    @Autowired
    private CardSubscriptionPersistenceAdapter adapter;

    private CardSubscription pending(String cardId, LocalDateTime leaseUntil) {
        return CardSubscription.builder()
                .id(UUID.randomUUID().toString())
                .cardId(cardId)
                .userId("user-1")
                .status(SubscriptionStatus.PENDING)
                .requestedAt(NOW)
                .attempts(1)
                .nextRetryAt(leaseUntil)
                .build();
    }

    @Test
    @DisplayName("insertIfAbsent — a second insert for the same card has no effect")
    void insertIfAbsent_allowsOneRowPerCard() {
        assertThat(adapter.insertIfAbsent(pending("h2-insert", NOW.plusMinutes(1)))).isTrue();
        assertThat(adapter.insertIfAbsent(pending("h2-insert", NOW.plusMinutes(1)))).isFalse();
    }

    @Test
    @DisplayName("claimForRetry — a PENDING row is claimable only after its lease expires")
    void claimForRetry_respectsLease() {
        adapter.insertIfAbsent(pending("h2-lease", NOW.plusMinutes(1)));

        assertThat(adapter.claimForRetry("h2-lease", "user-1", NOW, NOW.plusMinutes(1))).isFalse();
        assertThat(adapter.claimForRetry("h2-lease", "user-1", NOW.plusMinutes(2), NOW.plusMinutes(3))).isTrue();
        assertThat(adapter.findByCardId("h2-lease").orElseThrow().getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("claimForRetry — a FAILED row is claimable at once, a SUBSCRIBED row never")
    void claimForRetry_dependsOnStatus() {
        adapter.insertIfAbsent(pending("h2-status", NOW.plusMinutes(1)));
        CardSubscription row = adapter.findByCardId("h2-status").orElseThrow();

        row.markFailed("boom", NOW.plusHours(1));
        adapter.save(row);
        assertThat(adapter.claimForRetry("h2-status", "user-2", NOW, NOW.plusMinutes(1))).isTrue();

        CardSubscription claimed = adapter.findByCardId("h2-status").orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(claimed.getUserId()).isEqualTo("user-2");
        assertThat(claimed.getErrorMessage()).isNull();

        claimed.markSubscribed();
        adapter.save(claimed);
        assertThat(adapter.claimForRetry("h2-status", "user-2", NOW.plusYears(1), NOW.plusYears(2))).isFalse();
    }

    @Test
    @DisplayName("claimDue — two replicas selecting the same due row: only one claim succeeds")
    void claimDue_succeedsOnce() {
        adapter.insertIfAbsent(pending("h2-due", NOW.minusSeconds(1)));
        String id = adapter.findByCardId("h2-due").orElseThrow().getId();

        assertThat(adapter.findDueForRetry(NOW, 500)).extracting(CardSubscription::getId).contains(id);
        assertThat(adapter.claimDue(id, NOW, NOW.plusMinutes(1))).isTrue();
        assertThat(adapter.claimDue(id, NOW, NOW.plusMinutes(1))).isFalse();
        assertThat(adapter.findDueForRetry(NOW, 500)).extracting(CardSubscription::getId).doesNotContain(id);
    }
}
