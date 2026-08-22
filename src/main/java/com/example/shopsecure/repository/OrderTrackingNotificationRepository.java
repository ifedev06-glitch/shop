package com.example.shopsecure.repository;

import com.example.shopsecure.entity.CreatorRole;
import com.example.shopsecure.entity.OrderTrackingNotification;
import com.example.shopsecure.entity.OrderTrackingNotificationStatus;
import com.example.shopsecure.entity.OrderTrackingNotificationType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderTrackingNotificationRepository
        extends JpaRepository<OrderTrackingNotification, Long> {

    boolean existsByOrder_IdAndParticipantRoleAndNotificationType(
            Long orderId,
            CreatorRole participantRole,
            OrderTrackingNotificationType notificationType
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from OrderTrackingNotification n where n.id = :id")
    Optional<OrderTrackingNotification> findByIdForUpdate(@Param("id") Long id);

    @Query("select n.id from OrderTrackingNotification n "
            + "where n.order.id = :orderId and n.status = :status "
            + "and (n.nextAttemptAt is null or n.nextAttemptAt <= :now) order by n.id")
    List<Long> findIdsByOrderAndStatusDue(
            @Param("orderId") Long orderId,
            @Param("status") OrderTrackingNotificationStatus status,
            @Param("now") Instant now
    );

    @Query("select n.id from OrderTrackingNotification n "
            + "where n.order.id = :orderId and n.notificationType = :notificationType "
            + "and n.status = :status "
            + "and (n.nextAttemptAt is null or n.nextAttemptAt <= :now) order by n.id")
    List<Long> findIdsByOrderAndTypeAndStatusDue(
            @Param("orderId") Long orderId,
            @Param("notificationType") OrderTrackingNotificationType notificationType,
            @Param("status") OrderTrackingNotificationStatus status,
            @Param("now") Instant now
    );

    @Query("select n.id from OrderTrackingNotification n "
            + "where n.status = :status "
            + "and (n.nextAttemptAt is null or n.nextAttemptAt <= :now) "
            + "order by n.nextAttemptAt, n.id")
    List<Long> findIdsDue(
            @Param("status") OrderTrackingNotificationStatus status,
            @Param("now") Instant now,
            Pageable pageable
    );
}
