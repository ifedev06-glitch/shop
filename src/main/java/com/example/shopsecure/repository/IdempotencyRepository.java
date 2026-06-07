package com.example.shopsecure.repository;

import com.example.shopsecure.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKeyAndRequestPath(
            String idempotencyKey, String requestPath);

    void deleteByCreatedAtBefore(LocalDateTime dateTime);
}