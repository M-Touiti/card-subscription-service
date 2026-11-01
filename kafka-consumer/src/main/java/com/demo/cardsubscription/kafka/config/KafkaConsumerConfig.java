package com.demo.cardsubscription.kafka.config;

import com.demo.cardsubscription.kafka.dto.CardEventMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kafka consumer configuration.
 *
 * <p>Retry strategy: exponential back-off with 3 attempts.
 * After exhausting retries, the message is forwarded to the Dead Letter Topic (DLT)
 * via {@link DeadLetterPublishingRecoverer}. The DLT topic name is automatically
 * derived as {@code <original-topic>.DLT} by Spring Kafka.
 *
 * <p>A message that cannot be deserialized skips the retries and goes straight to the DLT
 * with its original bytes, so it cannot block the partition.
 */
@Slf4j
@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    private static final String DLT_SUFFIX = ".DLT";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Value("${spring.kafka.listener.concurrency:4}")
    private int concurrency;

    @Value("${kafka.topics.card-events}")
    private String cardEventsTopic;

    @Value("${kafka.topics.card-events-partitions:12}")
    private int partitions;

    @Value("${kafka.topics.replicas:1}")
    private int replicas;

    // ─── Topics ───────────────────────────────────────────────────────────────

    // Auto-creation would give one partition, which limits consumption to one consumer
    @Bean
    public NewTopic cardEventsTopic() {
        return TopicBuilder.name(cardEventsTopic).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic cardEventsDeadLetterTopic() {
        return TopicBuilder.name(cardEventsTopic + DLT_SUFFIX).partitions(partitions).replicas(replicas).build();
    }

    // ─── ConsumerFactory ──────────────────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, CardEventMessage> cardEventConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.demo.cardsubscription.*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, CardEventMessage.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    // ─── ListenerContainerFactory ─────────────────────────────────────────────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CardEventMessage>
    cardEventKafkaListenerContainerFactory(
            ConsumerFactory<String, CardEventMessage> cardEventConsumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, CardEventMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cardEventConsumerFactory);
        factory.setConcurrency(concurrency);
        factory.setCommonErrorHandler(cardEventErrorHandler(kafkaTemplate));
        return factory;
    }

    // ─── Error Handler (DLT + Retry) ──────────────────────────────────────────

    @Bean
    public DefaultErrorHandler cardEventErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterTemplates(kafkaTemplate),
                // A negative partition lets the producer choose, so the DLT need not match the source topic
                (record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));

        // Exponential back-off: 1s → 2s → 4s (max 3 retries before DLT)
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxAttempts(3);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        // Log the error on each retry attempt
        handler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Retry attempt {}/3 for topic={} partition={} offset={} – reason: {}",
                        deliveryAttempt,
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        ex.getMessage())
        );

        return handler;
    }

    // A malformed record is republished as its original bytes, which the JSON serializer would re-encode
    private Map<Class<?>, KafkaOperations<?, ?>> deadLetterTemplates(KafkaTemplate<String, Object> kafkaTemplate) {
        KafkaTemplate<String, byte[]> rawTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                kafkaTemplate.getProducerFactory().getConfigurationProperties(),
                new StringSerializer(),
                new ByteArraySerializer()));

        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, rawTemplate);
        templates.put(Object.class, kafkaTemplate);
        return templates;
    }
}
