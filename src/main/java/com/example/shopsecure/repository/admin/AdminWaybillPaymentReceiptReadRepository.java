package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.WaybillPaymentReceipt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface AdminWaybillPaymentReceiptReadRepository
        extends Repository<WaybillPaymentReceipt, Long> {

    @EntityGraph(attributePaths = "attempt")
    List<WaybillPaymentReceipt> findByWaybill_IdOrderByCreatedAtDesc(Long waybillId, Pageable pageable);
}
