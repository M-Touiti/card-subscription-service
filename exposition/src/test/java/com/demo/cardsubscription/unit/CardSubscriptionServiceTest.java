package com.demo.cardsubscription.unit;

import com.demo.cardsubscription.application.command.SubscribeCardCommand;
import com.demo.cardsubscription.application.port.out.CardApiPort;
import com.demo.cardsubscription.application.port.out.ClaimCardSubscriptionPort;
import com.demo.cardsubscription.application.port.out.LoadCardSubscriptionPort;
import com.demo.cardsubscription.application.port.out.SaveCardSubscriptionPort;
import com.demo.cardsubscription.application.service.CardSubscriptionService;
import com.demo.cardsubscription.application.service.SubscriptionRetryPolicy;
import com.demo.cardsubscription.domain.exception.CardSubscriptionNotFoundException;
import com.demo.cardsubscription.domain.model.CardSubscription;
import com.demo.cardsubscription.domain.model.SubscriptionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardSubscriptionServiceTest {

    @Mock
    private SaveCardSubscriptionPort saveCardSubscriptionPort;

    @Mock
    private LoadCardSubscriptionPort loadCardSubscriptionPort;

    @Mock
    private ClaimCardSubscriptionPort claimCardSubscriptionPort;

    @Mock
    private CardApiPort cardApiPort;

    private final SubscriptionRetryPolicy retryPolicy = new SubscriptionRetryPolicy(
            Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofMinutes(30), 3, 50);

    private CardSubscriptionService service;

    private SubscribeCardCommand command;

    @BeforeEach
    void setUp() {
        service = new CardSubscriptionService(saveCardSubscriptionPort, loadCardSubscriptionPort,
                claimCardSubscriptionPort, cardApiPort, retryPolicy);
        command = new SubscribeCardCommand("card-123", "user-456");
    }

    private CardSubscription subscription(SubscriptionStatus status, int attempts) {
        return CardSubscription.builder()
                .id("existing-id")
                .cardId("card-123")
                .userId("user-456")
                .status(status)
                .requestedAt(LocalDateTime.now())
                .attempts(attempts)
                .build();
    }

    @Test
    @DisplayName("subscribe – happy path – should persist SUBSCRIBED status")
    void subscribe_success() {
        // given
        when(loadCardSubscriptionPort.findByCardId("card-123")).thenReturn(Optional.empty());
        when(claimCardSubscriptionPort.insertIfAbsent(any())).thenReturn(true);
        when(saveCardSubscriptionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cardApiPort.subscribeCard("card-123", "user-456")).thenReturn(true);

        // when
        CardSubscription result = service.subscribe(command);

        // then
        assertThat(result.getStatus()).isEqualTo(SubscriptionStatus.SUBSCRIBED);
        assertThat(result.getProcessedAt()).isNotNull();
        assertThat(result.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("subscribe – external API returns false – should persist FAILED status and schedule a retry")
    void subscribe_apiFailure() {
        when(loadCardSubscriptionPort.findByCardId(any())).thenReturn(Optional.empty());
        when(claimCardSubscriptionPort.insertIfAbsent(any())).thenReturn(true);
        when(saveCardSubscriptionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cardApiPort.subscribeCard(any(), any())).thenReturn(false);

        CardSubscription result = service.subscribe(command);

        assertThat(result.getStatus()).isEqualTo(SubscriptionStatus.FAILED);
        assertThat(result.getErrorMessage()).isNotBlank();
        assertThat(result.getNextRetryAt()).isNotNull();
    }

    @Test
    @DisplayName("subscribe – card already active – should return existing record without calling API")
    void subscribe_idempotent() {
        when(loadCardSubscriptionPort.findByCardId("card-123"))
                .thenReturn(Optional.of(subscription(SubscriptionStatus.SUBSCRIBED, 1)));

        CardSubscription result = service.subscribe(command);

        assertThat(result.getId()).isEqualTo("existing-id");
        verifyNoInteractions(cardApiPort);
        verify(saveCardSubscriptionPort, never()).save(any());
    }

    @Test
    @DisplayName("subscribe – insert lost to a concurrent caller – should return its record without calling API")
    void subscribe_lostInsertRace() {
        when(loadCardSubscriptionPort.findByCardId("card-123"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(subscription(SubscriptionStatus.PENDING, 1)));
        when(claimCardSubscriptionPort.insertIfAbsent(any())).thenReturn(false);
        when(claimCardSubscriptionPort.claimForRetry(any(), any(), any(), any())).thenReturn(false);

        CardSubscription result = service.subscribe(command);

        assertThat(result.getStatus()).isEqualTo(SubscriptionStatus.PENDING);
        verifyNoInteractions(cardApiPort);
    }

    @Test
    @DisplayName("subscribe – card previously FAILED – should retry on the same record")
    void subscribe_retriesFailedOnSameRow() {
        when(loadCardSubscriptionPort.findByCardId("card-123"))
                .thenReturn(Optional.of(subscription(SubscriptionStatus.FAILED, 1)))
                .thenReturn(Optional.of(subscription(SubscriptionStatus.PENDING, 2)));
        when(claimCardSubscriptionPort.claimForRetry(eq("card-123"), eq("user-456"), any(), any())).thenReturn(true);
        when(saveCardSubscriptionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cardApiPort.subscribeCard("card-123", "user-456")).thenReturn(true);

        CardSubscription result = service.subscribe(command);

        assertThat(result.getId()).isEqualTo("existing-id");
        assertThat(result.getStatus()).isEqualTo(SubscriptionStatus.SUBSCRIBED);
        verify(claimCardSubscriptionPort, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("retryDue – only claimed subscriptions call the API")
    void retryDue_callsApiOnlyForClaimedRows() {
        CardSubscription due = subscription(SubscriptionStatus.FAILED, 1);
        when(loadCardSubscriptionPort.findDueForRetry(any(), eq(50))).thenReturn(List.of(due));
        when(claimCardSubscriptionPort.claimDue(eq("existing-id"), any(), any())).thenReturn(false);

        assertThat(service.retryDue()).isZero();
        verifyNoInteractions(cardApiPort);
    }

    @Test
    @DisplayName("retryDue – failure on the last attempt – should stop scheduling retries")
    void retryDue_stopsAfterMaxAttempts() {
        when(loadCardSubscriptionPort.findDueForRetry(any(), eq(50)))
                .thenReturn(List.of(subscription(SubscriptionStatus.FAILED, 2)));
        when(claimCardSubscriptionPort.claimDue(eq("existing-id"), any(), any())).thenReturn(true);
        when(loadCardSubscriptionPort.findById("existing-id"))
                .thenReturn(Optional.of(subscription(SubscriptionStatus.PENDING, 3)));
        when(saveCardSubscriptionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cardApiPort.subscribeCard(any(), any())).thenReturn(false);

        assertThat(service.retryDue()).isEqualTo(1);

        ArgumentCaptor<CardSubscription> saved = ArgumentCaptor.forClass(CardSubscription.class);
        verify(saveCardSubscriptionPort).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SubscriptionStatus.FAILED);
        assertThat(saved.getValue().getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("retry policy – delay doubles per attempt up to the maximum")
    void retryPolicy_backsOffExponentially() {
        SubscriptionRetryPolicy policy = new SubscriptionRetryPolicy(
                Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofSeconds(100), 10, 50);
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);

        assertThat(policy.nextRetryAt(now, 1)).isEqualTo(now.plusSeconds(30));
        assertThat(policy.nextRetryAt(now, 2)).isEqualTo(now.plusSeconds(60));
        assertThat(policy.nextRetryAt(now, 3)).isEqualTo(now.plusSeconds(100));
        assertThat(policy.nextRetryAt(now, 10)).isNull();
    }

    @Test
    @DisplayName("getByCardId – not found – should throw CardSubscriptionNotFoundException")
    void getByCardId_notFound() {
        when(loadCardSubscriptionPort.findByCardId("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByCardId("unknown"))
                .isInstanceOf(CardSubscriptionNotFoundException.class)
                .hasMessageContaining("unknown");
    }
}
