package com.example.shopsecure.repository;

import com.example.shopsecure.entity.ShippingQuote;
import com.example.shopsecure.entity.ShippingQuoteStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShippingQuoteRepository extends JpaRepository<ShippingQuote, Long> {
    List<ShippingQuote> findByShipment_IdAndStatusOrderByDeliveryFeeAsc(
            Long shipmentId,
            ShippingQuoteStatus status
    );

    Optional<ShippingQuote> findByShipment_Order_IdAndStatus(
            Long orderId,
            ShippingQuoteStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from ShippingQuote q where q.id = :quoteId and q.shipment.order.id = :orderId")
    Optional<ShippingQuote> findByIdAndOrderIdForUpdate(
            @Param("quoteId") Long quoteId,
            @Param("orderId") Long orderId
    );

    List<ShippingQuote> findByShipment_Id(Long shipmentId);

    Optional<ShippingQuote> findByShipment_IdAndProviderRateId(Long shipmentId, String providerRateId);
}
