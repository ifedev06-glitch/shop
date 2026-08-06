package com.example.shopsecure.repository;

import com.example.shopsecure.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByShareToken(String shareToken);

    boolean existsByShareToken(String shareToken);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.shareToken = :shareToken")
    Optional<Order> findByShareTokenForUpdate(@Param("shareToken") String shareToken);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

}
