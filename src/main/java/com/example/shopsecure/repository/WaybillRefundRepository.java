package com.example.shopsecure.repository;

import com.example.shopsecure.entity.WaybillRefund;
import com.example.shopsecure.entity.WaybillRefundStatus;
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

public interface WaybillRefundRepository extends JpaRepository<WaybillRefund, Long> {

    Optional<WaybillRefund> findByPaymentAttempt_Id(Long paymentAttemptId);

    Optional<WaybillRefund> findByProviderRefundId(String providerRefundId);

    Optional<WaybillRefund> findByProviderRefundReference(String providerRefundReference);

    List<WaybillRefund> findByWaybill_IdOrderByCreatedAtDesc(Long waybillId);

    @Query("select r.id from WaybillRefund r "
            + "where r.status in :statuses and r.updatedAt <= :updatedBefore "
            + "order by r.updatedAt, r.id")
    List<Long> findIdsDueForReconciliation(
            @Param("statuses") Collection<WaybillRefundStatus> statuses,
            @Param("updatedBefore") LocalDateTime updatedBefore,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WaybillRefund r join fetch r.waybill where r.id = :id")
    Optional<WaybillRefund> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WaybillRefund r join fetch r.waybill "
            + "where r.paymentAttempt.id = :paymentAttemptId")
    Optional<WaybillRefund> findByPaymentAttemptIdForUpdate(
            @Param("paymentAttemptId") Long paymentAttemptId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WaybillRefund r join fetch r.waybill "
            + "where r.providerRefundId = :providerRefundId")
    Optional<WaybillRefund> findByProviderRefundIdForUpdate(
            @Param("providerRefundId") String providerRefundId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WaybillRefund r join fetch r.waybill "
            + "where r.providerRefundReference = :providerRefundReference")
    Optional<WaybillRefund> findByProviderRefundReferenceForUpdate(
            @Param("providerRefundReference") String providerRefundReference
    );
}
