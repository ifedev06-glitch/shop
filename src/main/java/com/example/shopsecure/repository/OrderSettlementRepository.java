package com.example.shopsecure.repository;

import com.example.shopsecure.entity.OrderSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.List;
import java.time.Instant;
import com.example.shopsecure.entity.SettlementStatus;
import com.example.shopsecure.entity.SettlementType;

public interface OrderSettlementRepository extends JpaRepository<OrderSettlement, Long> {
    @EntityGraph(attributePaths = "order")
    Optional<OrderSettlement> findByOrder_Id(Long orderId);

    Optional<OrderSettlement> findByPayoutReference(String payoutReference);

    Optional<OrderSettlement> findByTransferId(String transferId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OrderSettlement s where s.order.id = :orderId")
    Optional<OrderSettlement> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "order")
    @Query("select s from OrderSettlement s where s.id = :settlementId")
    Optional<OrderSettlement> findByIdForUpdate(@Param("settlementId") Long settlementId);

    @EntityGraph(attributePaths = "order")
    @Query("select s from OrderSettlement s where s.settlementType = :settlementType "
            + "and s.status in :statuses "
            + "and (s.nextReconciliationAt is null or s.nextReconciliationAt <= :now) "
            + "order by s.nextReconciliationAt, s.id")
    List<OrderSettlement> findDueForReconciliation(
            @Param("settlementType") SettlementType settlementType,
            @Param("statuses") List<SettlementStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable
    );
}
