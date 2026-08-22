package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.WaybillPaymentAttempt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface AdminWaybillPaymentAttemptReadRepository
        extends Repository<WaybillPaymentAttempt, Long> {

    @EntityGraph(attributePaths = "invitation")
    List<WaybillPaymentAttempt> findByWaybill_IdOrderByCreatedAtDesc(Long waybillId, Pageable pageable);
}
