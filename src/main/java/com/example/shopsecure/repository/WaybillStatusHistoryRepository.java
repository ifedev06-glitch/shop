package com.example.shopsecure.repository;

import com.example.shopsecure.entity.WaybillStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WaybillStatusHistoryRepository extends JpaRepository<WaybillStatusHistory, Long> {

    List<WaybillStatusHistory> findByWaybill_IdOrderByCreatedAtAscIdAsc(Long waybillId);
}
