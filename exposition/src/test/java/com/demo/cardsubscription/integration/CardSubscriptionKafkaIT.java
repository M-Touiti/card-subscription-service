package com.demo.cardsubscription.integration;

import com.demo.cardsubscription.application.port.out.CardApiPort;
import com.demo.cardsubscription.application.port.out.LoadCardSubscriptionPort;
import com.demo.cardsubscription.domain.model.SubscriptionStatus;
import com.demo.cardsubscription.kafka.dto.CardEventMessage;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Test
    @DisplayName("Malformed message goes to the DLT unchanged and the next message on its partition is processed")
    void givenMalformedMessage_whenConsumed_thenDeadLetteredAndPartitionContinues() throws Exception {
        byte[] malformed = ("not json " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        byte[] valid = """
                {"eventId":"evt-kafka-003","eventType":"CARD_CREATED","cardId":"CARD-KAFKA-003",\
                "userId":"USER-KAFKA-003","eventTimestamp":"2024-05-10T10:00:00"}""".getBytes(StandardCharsets.UTF_8);

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                new StringSerializer(), new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(cardEventsTopic, 0, null, malformed)).get(5, SECONDS);
            producer.send(new ProducerRecord<>(cardEventsTopic, 0, null, valid)).get(5, SECONDS);
        }

        await().atMost(15, SECONDS).untilAsserted(() ->
                assertThat(loadCardSubscriptionPort.findByCardId("CARD-KAFKA-003")).isPresent());

        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-check-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(cardEventsTopic + ".DLT"));
            List<byte[]> deadLetters = new ArrayList<>();
            await().atMost(15, SECONDS).untilAsserted(() -> {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    deadLetters.add(record.value());
                }
                assertThat(deadLetters).contains(malformed);
            });
        }
    }

    @Test
    @DisplayName("The topic and its DLT are created with several partitions")
    void topicsAreCreatedWithSeveralPartitions() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            var topics = admin.describeTopics(List.of(cardEventsTopic, cardEventsTopic + ".DLT"))
                    .allTopicNames().get(10, SECONDS);

            assertThat(topics.values()).allSatisfy(topic -> assertThat(topic.partitions()).hasSize(12));
        }
    }
}
