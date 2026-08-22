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
            + "where s.provider = :provider and s.providerShipmentId = :providerShipmentId")
    Optional<Long> findOrderIdByProviderAndProviderShipmentId(
            @Param("provider") String provider,
            @Param("providerShipmentId") String providerShipmentId
    );

    @Query("select s.order.id from OrderShipment s "
            + "where s.order.status in :statuses "
            + "and (s.nextBookingAttemptAt is null or s.nextBookingAttemptAt <= :now) "
            + "order by s.updatedAt")
    List<Long> findOrderIdsReadyForBookingReconciliation(
            @Param("statuses") Collection<OrderStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Query("select s.order.id from OrderShipment s "
            + "where s.provider = :provider "
             + "and s.status in :shipmentStatuses "
             + "and s.order.status in :orderStatuses "
             + "and s.order.id > :afterOrderId "
             + "and s.order.id <= :throughOrderId "
             + "order by s.order.id")
    List<Long> findOrderIdsForProviderTracking(
            @Param("provider") String provider,
            @Param("shipmentStatuses") Collection<com.example.shopsecure.provider.shipping.ShipmentStatus> shipmentStatuses,
             @Param("orderStatuses") Collection<OrderStatus> orderStatuses,
             @Param("afterOrderId") long afterOrderId,
             @Param("throughOrderId") long throughOrderId,
             Pageable pageable
     );

    @Query("select max(s.order.id) from OrderShipment s "
            + "where s.provider = :provider "
            + "and s.status in :shipmentStatuses "
            + "and s.order.status in :orderStatuses")
    Long findProviderTrackingHighWaterOrderId(
            @Param("provider") String provider,
            @Param("shipmentStatuses") Collection<com.example.shopsecure.provider.shipping.ShipmentStatus> shipmentStatuses,
            @Param("orderStatuses") Collection<OrderStatus> orderStatuses
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
