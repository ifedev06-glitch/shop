package com.example.shopsecure.repository;

import com.example.shopsecure.entity.ProcessedWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedWebhookEventRepository extends JpaRepository<ProcessedWebhookEvent, Long> {
    boolean existsByProviderAndEventKey(String provider, String eventKey);
}
