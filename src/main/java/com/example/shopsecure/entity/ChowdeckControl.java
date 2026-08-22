package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "chowdeck_control")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChowdeckControl {

    @Id
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "configured_mode", nullable = false, length = 16)
    private ChowdeckMode configuredMode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_user_id")
    private User updatedBy;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;
}
