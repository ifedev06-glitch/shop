package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.WaybillRefund;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface AdminWaybillRefundReadRepository extends Repository<WaybillRefund, Long> {

    @EntityGraph(attributePaths = "paymentAttempt")
    List<WaybillRefund> findByWaybill_IdOrderByCreatedAtDesc(Long waybillId, Pageable pageable);
}
