package com.example.shopsecure.repository;

import com.example.shopsecure.entity.WaybillEmailNotification;
import com.example.shopsecure.entity.WaybillEmailNotificationStatus;
import com.example.shopsecure.entity.WaybillEmailNotificationType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WaybillEmailNotificationRepository
        extends JpaRepository<WaybillEmailNotification, Long> {

    boolean existsByWaybill_IdAndParty_IdAndNotificationType(
            Long waybillId,
            Long partyId,
            WaybillEmailNotificationType notificationType
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from WaybillEmailNotification n where n.id = :id")
    Optional<WaybillEmailNotification> findByIdForUpdate(@Param("id") Long id);

    @Query("select n.id from WaybillEmailNotification n "
            + "where n.waybill.id = :waybillId and n.status = :status "
            + "and (n.nextAttemptAt is null or n.nextAttemptAt <= :now) order by n.id")
    List<Long> findIdsByWaybillAndStatusDue(
            @Param("waybillId") Long waybillId,
            @Param("status") WaybillEmailNotificationStatus status,
            @Param("now") LocalDateTime now
    );

    @Query("select n.id from WaybillEmailNotification n "
            + "where n.status = :status "
            + "and (n.nextAttemptAt is null or n.nextAttemptAt <= :now) "
            + "order by n.nextAttemptAt, n.id")
    List<Long> findIdsDue(
            @Param("status") WaybillEmailNotificationStatus status,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );
}
