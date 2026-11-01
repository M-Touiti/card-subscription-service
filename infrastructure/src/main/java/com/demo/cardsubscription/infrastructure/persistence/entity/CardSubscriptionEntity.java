package com.demo.cardsubscription.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "card_subscription",
        uniqueConstraints = @UniqueConstraint(name = "uk_card_subscription_card_id", columnNames = "card_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CardSubscriptionEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private String id;

    @Column(name = "card_id", nullable = false)
    private String cardId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String status;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;
}
