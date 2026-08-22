package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

@Entity
@Immutable
@Table(name = "chowdeck_control_history", indexes = {
        @Index(name = "idx_chowdeck_control_history_changed", columnList = "control_version")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChowdeckControlHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_mode", nullable = false, length = 16)
    private ChowdeckMode fromMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_mode", nullable = false, length = 16)
    private ChowdeckMode toMode;

    @Column(nullable = false, length = 500)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "changed_by_user_id", nullable = false)
    private User changedBy;

    @Column(nullable = false)
    private Instant changedAt;

    @Column(nullable = false, unique = true)
    private long controlVersion;
}
