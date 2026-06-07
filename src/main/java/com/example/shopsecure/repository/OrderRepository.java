package com.example.shopsecure.repository;

import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;


public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNumber(String orderNumber);

    Optional<Order> findByPaymentReference(String paymentReference);

    List<Order> findByBusiness_IdAndStatusAndPaymentStatus(
            Long id,
            OrderStatus orderStatus,
            String paid
    );

    List<Order> findByPaymentStatus(String paymentStatus);

    List<Order> findByStatusAndPaymentStatus(
            OrderStatus status,
            String paymentStatus
    );

    List<Order> findByBusiness_Id(Long businessId);

    List<Order> findByBusiness_BusinessNameContainingIgnoreCase(
            String businessName
    );

    List<Order> findByBusiness_IdAndStatus(Long businessId, OrderStatus status);

    @Query("SELECT o FROM Order o WHERE o.business.id = :businessId ORDER BY o.createdAt DESC")
    Page<Order> findOrdersByBusinessId(@Param("businessId") Long businessId, Pageable pageable);

    Page<Order> findByStatusAndPaymentStatus(OrderStatus status, String paymentStatus, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);



}