package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.WaybillStatusHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface AdminWaybillHistoryReadRepository extends Repository<WaybillStatusHistory, Long> {
    List<WaybillStatusHistory> findByWaybill_IdOrderByCreatedAtDesc(Long waybillId, Pageable pageable);
}
