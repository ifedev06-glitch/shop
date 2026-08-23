package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "order_disputes", indexes = {
        @Index(name = "idx_order_disputes_status_created", columnList = "status, created_at, id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_order_disputes_order", columnNames = "order_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderDisputeStatus status = OrderDisputeStatus.OPEN;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;
}
