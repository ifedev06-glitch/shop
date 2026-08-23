package com.example.shopsecure.repository;

import com.example.shopsecure.entity.OrderDisputeNotification;
import com.example.shopsecure.entity.OrderDisputeNotificationAudience;
import com.example.shopsecure.entity.OrderDisputeNotificationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderDisputeNotificationRepository
        extends JpaRepository<OrderDisputeNotification, Long> {

    boolean existsByDispute_IdAndAudience(Long disputeId, OrderDisputeNotificationAudience audience);

    List<OrderDisputeNotification> findByDispute_IdOrderByAudienceAsc(Long disputeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from OrderDisputeNotification n where n.id = :id")
    Optional<OrderDisputeNotification> findByIdForUpdate(@Param("id") Long id);

    @Query("select n.id from OrderDisputeNotification n "
            + "where n.dispute.id = :disputeId and n.status = :status "
            + "and (n.nextAttemptAt is null or n.nextAttemptAt <= :now) order by n.id")
    List<Long> findIdsByDisputeAndStatusDue(
            @Param("disputeId") Long disputeId,
            @Param("status") OrderDisputeNotificationStatus status,
            @Param("now") Instant now
    );

    @Query("select n.id from OrderDisputeNotification n "
            + "where n.status = :status "
            + "and (n.nextAttemptAt is null or n.nextAttemptAt <= :now) "
            + "order by n.nextAttemptAt, n.id")
    List<Long> findIdsDue(
            @Param("status") OrderDisputeNotificationStatus status,
            @Param("now") Instant now,
            Pageable pageable
    );
}
