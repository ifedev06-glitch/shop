package com.example.shopsecure.repository;

import com.example.shopsecure.entity.OrderCounterparty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderCounterpartyRepository extends JpaRepository<OrderCounterparty, Long> {
    Optional<OrderCounterparty> findByOrder_Id(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from OrderCounterparty c where c.order.id = :orderId")
    Optional<OrderCounterparty> findByOrderIdForUpdate(@Param("orderId") Long orderId);
}
