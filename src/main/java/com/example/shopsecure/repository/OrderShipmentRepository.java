package com.example.shopsecure.repository;

import com.example.shopsecure.entity.OrderShipment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import com.example.shopsecure.entity.OrderStatus;
import org.springframework.data.domain.Pageable;

public interface OrderShipmentRepository extends JpaRepository<OrderShipment, Long> {
    Optional<OrderShipment> findByOrder_Id(Long orderId);

    Optional<OrderShipment> findByProviderShipmentId(String providerShipmentId);

    @Query("select s.order.id from OrderShipment s "
            + "where s.order.status in :statuses "
            + "and (s.nextBookingAttemptAt is null or s.nextBookingAttemptAt <= :now) "
            + "order by s.updatedAt")
    List<Long> findOrderIdsReadyForBookingReconciliation(
            @Param("statuses") Collection<OrderStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OrderShipment s where s.order.id = :orderId")
    Optional<OrderShipment> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OrderShipment s where s.providerShipmentId = :providerShipmentId")
    Optional<OrderShipment> findByProviderShipmentIdForUpdate(
            @Param("providerShipmentId") String providerShipmentId
    );
}
