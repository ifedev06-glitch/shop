package com.example.shopsecure.repository;

import com.example.shopsecure.entity.OrderCounterparty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderCounterpartyRepository extends JpaRepository<OrderCounterparty, Long> {
    Optional<OrderCounterparty> findByOrder_Id(Long orderId);
}
