package com.demo.cardsubscription.integration;

import com.demo.cardsubscription.application.port.out.CardApiPort;
import com.demo.cardsubscription.application.port.out.LoadCardSubscriptionPort;
import com.demo.cardsubscription.domain.model.SubscriptionStatus;
import com.demo.cardsubscription.kafka.dto.CardEventMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("Card Subscription Kafka Consumer — integration")
class CardSubscriptionKafkaIT extends AbstractIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private LoadCardSubscriptionPort loadCardSubscriptionPort;

    @MockBean
    private CardApiPort cardApiPort;

    @Value("${kafka.topics.card-events}")
    private String cardEventsTopic;

    @BeforeEach
    void stubApiSuccess() {
        when(cardApiPort.subscribeCard(any(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("Kafka event consumed → subscription persisted as SUBSCRIBED")
    void givenCardEvent_whenConsumed_thenSubscriptionCreated() throws Exception {
        CardEventMessage event = new CardEventMessage(
                "evt-kafka-001", "CARD_CREATED", "CARD-KAFKA-001", "USER-KAFKA-001",
                LocalDateTime.of(2024, 5, 10, 10, 0));

        kafkaTemplate.send(cardEventsTopic, event).get(5, SECONDS);

        await().atMost(15, SECONDS).untilAsserted(() -> {
            var subscription = loadCardSubscriptionPort.findByCardId("CARD-KAFKA-001");
            assertThat(subscription).isPresent();
            assertThat(subscription.get().getStatus()).isEqualTo(SubscriptionStatus.SUBSCRIBED);
            assertThat(subscription.get().getProcessedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("Kafka event consumed → subscription persisted as FAILED when API returns false")
    void givenCardEvent_whenApiReturnsFalse_thenSubscriptionFailed() throws Exception {
        when(cardApiPort.subscribeCard(any(), any())).thenReturn(false);

        CardEventMessage event = new CardEventMessage(
                "evt-kafka-002", "CARD_CREATED", "CARD-KAFKA-002", "USER-KAFKA-002",
                LocalDateTime.of(2024, 5, 10, 10, 0));

        kafkaTemplate.send(cardEventsTopic, event).get(5, SECONDS);

        await().atMost(15, SECONDS).untilAsserted(() -> {
            var subscription = loadCardSubscriptionPort.findByCardId("CARD-KAFKA-002");
            assertThat(subscription).isPresent();
            assertThat(subscription.get().getStatus()).isEqualTo(SubscriptionStatus.FAILED);
            assertThat(subscription.get().getErrorMessage()).isNotBlank();
        });
    }
}
