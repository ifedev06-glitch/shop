package com.example.shopsecure.repository;

import com.example.shopsecure.entity.WaybillShipment;
import com.example.shopsecure.entity.WaybillStatus;
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

public interface WaybillShipmentRepository extends JpaRepository<WaybillShipment, Long> {

    Optional<WaybillShipment> findByWaybill_Id(Long waybillId);

    Optional<WaybillShipment> findByProviderShipmentId(String providerShipmentId);

    @Query("select s.waybill.id from WaybillShipment s "
            + "where s.provider = :provider and s.providerShipmentId = :providerShipmentId")
    Optional<Long> findWaybillIdByProviderAndProviderShipmentId(
            @Param("provider") String provider,
            @Param("providerShipmentId") String providerShipmentId
    );

    @Query("select s.waybill.id from WaybillShipment s "
            + "where s.waybill.status in :statuses "
            + "and (s.nextBookingAttemptAt is null or s.nextBookingAttemptAt <= :now) "
            + "order by s.updatedAt, s.id")
    List<Long> findWaybillIdsDueForBooking(
            @Param("statuses") Collection<WaybillStatus> statuses,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Query("select s.waybill.id from WaybillShipment s "
            + "where s.provider = :provider "
            + "and s.status in :shipmentStatuses "
            + "and s.waybill.status in :waybillStatuses "
            + "and s.waybill.id > :afterWaybillId "
            + "and s.waybill.id <= :throughWaybillId "
            + "order by s.waybill.id")
    List<Long> findWaybillIdsForProviderTracking(
            @Param("provider") String provider,
            @Param("shipmentStatuses") Collection<com.example.shopsecure.provider.shipping.ShipmentStatus> shipmentStatuses,
            @Param("waybillStatuses") Collection<WaybillStatus> waybillStatuses,
            @Param("afterWaybillId") long afterWaybillId,
            @Param("throughWaybillId") long throughWaybillId,
            Pageable pageable
    );

    @Query("select max(s.waybill.id) from WaybillShipment s "
            + "where s.provider = :provider "
            + "and s.status in :shipmentStatuses "
            + "and s.waybill.status in :waybillStatuses")
    Long findProviderTrackingHighWaterWaybillId(
            @Param("provider") String provider,
            @Param("shipmentStatuses") Collection<com.example.shopsecure.provider.shipping.ShipmentStatus> shipmentStatuses,
            @Param("waybillStatuses") Collection<WaybillStatus> waybillStatuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from WaybillShipment s where s.waybill.id = :waybillId")
    Optional<WaybillShipment> findByWaybillIdForUpdate(@Param("waybillId") Long waybillId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from WaybillShipment s where s.providerShipmentId = :providerShipmentId")
    Optional<WaybillShipment> findByProviderShipmentIdForUpdate(
            @Param("providerShipmentId") String providerShipmentId
    );
}
