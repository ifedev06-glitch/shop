package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.OrderStatusHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface AdminOrderHistoryReadRepository extends Repository<OrderStatusHistory, Long> {
    List<OrderStatusHistory> findByOrder_IdOrderByCreatedAtDesc(Long orderId, Pageable pageable);
}
