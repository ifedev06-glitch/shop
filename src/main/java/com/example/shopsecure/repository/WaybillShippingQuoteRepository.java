package com.example.shopsecure.repository;

import com.example.shopsecure.entity.ShippingQuoteStatus;
import com.example.shopsecure.entity.WaybillShippingQuote;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WaybillShippingQuoteRepository extends JpaRepository<WaybillShippingQuote, Long> {

    List<WaybillShippingQuote> findByShipment_Id(Long shipmentId);

    List<WaybillShippingQuote> findByShipment_IdAndStatusOrderByDeliveryFeeAsc(
            Long shipmentId,
            ShippingQuoteStatus status
    );

    List<WaybillShippingQuote> findByShipment_IdAndStatusAndExpiresAtAfterOrderByDeliveryFeeAsc(
            Long shipmentId,
            ShippingQuoteStatus status,
            LocalDateTime now
    );

    Optional<WaybillShippingQuote> findByShipment_IdAndProviderRateId(
            Long shipmentId,
            String providerRateId
    );

    Optional<WaybillShippingQuote> findByShipment_Waybill_IdAndStatus(
            Long waybillId,
            ShippingQuoteStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from WaybillShippingQuote q join fetch q.shipment s join fetch s.waybill "
            + "where q.id = :quoteId and s.waybill.id = :waybillId")
    Optional<WaybillShippingQuote> findByIdAndWaybillIdForUpdate(
            @Param("quoteId") Long quoteId,
            @Param("waybillId") Long waybillId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from WaybillShippingQuote q "
            + "where q.shipment.waybill.id = :waybillId and q.status = :status")
    Optional<WaybillShippingQuote> findByWaybillIdAndStatusForUpdate(
            @Param("waybillId") Long waybillId,
            @Param("status") ShippingQuoteStatus status
    );
}
