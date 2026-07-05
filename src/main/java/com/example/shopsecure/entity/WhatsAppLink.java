package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "whatsapp_links", indexes = {
        @Index(name = "idx_whatsapp_links_business_id", columnList = "business_id", unique = true),
        @Index(name = "idx_whatsapp_links_phone", columnList = "phoneNumber", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(nullable = false, unique = true)
    private String phoneNumber;

    private LocalDateTime createdAt;
}
