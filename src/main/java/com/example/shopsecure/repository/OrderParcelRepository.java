package com.example.shopsecure.repository;

import com.example.shopsecure.entity.OrderParcel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderParcelRepository extends JpaRepository<OrderParcel, Long> {
    Optional<OrderParcel> findByOrder_Id(Long orderId);
}
