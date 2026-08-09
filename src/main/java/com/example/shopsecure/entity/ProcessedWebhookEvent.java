package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "processed_webhook_events", indexes = {
        @Index(name = "idx_processed_webhook_provider_event", columnList = "provider, event_key", unique = true)
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(name = "event_key", nullable = false, length = 160)
    private String eventKey;

    @Column(nullable = false)
    private Instant createdAt;
}
