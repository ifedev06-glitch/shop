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
import com.example.shopsecure.entity.SettlementStatus;
import com.example.shopsecure.entity.SettlementType;

public interface OrderSettlementRepository extends JpaRepository<OrderSettlement, Long> {
    Optional<OrderSettlement> findByOrder_Id(Long orderId);

    Optional<OrderSettlement> findByPayoutReference(String payoutReference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OrderSettlement s where s.order.id = :orderId")
    Optional<OrderSettlement> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OrderSettlement s where s.payoutReference = :reference")
    Optional<OrderSettlement> findByPayoutReferenceForUpdate(@Param("reference") String reference);

    @EntityGraph(attributePaths = "order")
    List<OrderSettlement> findBySettlementTypeAndStatusIn(
            SettlementType settlementType,
            List<SettlementStatus> statuses,
            Pageable pageable
    );
}
