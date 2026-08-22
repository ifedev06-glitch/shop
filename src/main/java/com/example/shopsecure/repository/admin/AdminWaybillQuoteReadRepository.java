package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.WaybillShippingQuote;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface AdminWaybillQuoteReadRepository extends Repository<WaybillShippingQuote, Long> {
    List<WaybillShippingQuote> findByShipment_IdOrderByCreatedAtDesc(Long shipmentId, Pageable pageable);
}
