package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "idempotency_records", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"idempotency_key", "request_path"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_path", nullable = false)
    private String requestPath;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    private int statusCode;

    private LocalDateTime createdAt;
}