package com.example.shopsecure.repository;

import com.example.shopsecure.entity.WaybillPaymentAttempt;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WaybillPaymentAttemptRepository extends JpaRepository<WaybillPaymentAttempt, Long> {

    boolean existsByReference(String reference);

    boolean existsByWaybill_IdAndStatus(Long waybillId, WaybillPaymentAttemptStatus status);

    Optional<WaybillPaymentAttempt> findByReference(String reference);

    Optional<WaybillPaymentAttempt> findByWaybill_IdAndIdempotencyKey(
            Long waybillId,
            String idempotencyKey
    );

    Optional<WaybillPaymentAttempt> findFirstByWaybill_IdAndStatusInOrderByCreatedAtDesc(
            Long waybillId,
            Collection<WaybillPaymentAttemptStatus> statuses
    );

    List<WaybillPaymentAttempt> findByWaybill_IdAndStatusInOrderByCreatedAtDesc(
            Long waybillId,
            Collection<WaybillPaymentAttemptStatus> statuses
    );

    @Query("select a.id from WaybillPaymentAttempt a "
            + "where a.status in :statuses "
            + "and (a.nextRecheckAt is null or a.nextRecheckAt <= :now) "
            + "order by a.updatedAt, a.id")
    List<Long> findIdsDueForRecheck(
            @Param("statuses") Collection<WaybillPaymentAttemptStatus> statuses,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from WaybillPaymentAttempt a join fetch a.waybill where a.id = :id")
    Optional<WaybillPaymentAttempt> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from WaybillPaymentAttempt a join fetch a.waybill where a.reference = :reference")
    Optional<WaybillPaymentAttempt> findByReferenceForUpdate(@Param("reference") String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from WaybillPaymentAttempt a "
            + "where a.waybill.id = :waybillId and a.idempotencyKey = :idempotencyKey")
    Optional<WaybillPaymentAttempt> findByWaybillIdAndIdempotencyKeyForUpdate(
            @Param("waybillId") Long waybillId,
            @Param("idempotencyKey") String idempotencyKey
    );
}
