package com.example.shopsecure.repository;

import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNumber(String orderNumber);
    List<Order> findByBusiness_IdAndStatusAndPaymentStatus(Long id, OrderStatus orderStatus, String paid);
}