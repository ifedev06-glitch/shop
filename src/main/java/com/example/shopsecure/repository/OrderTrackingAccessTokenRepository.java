package com.example.shopsecure.repository;

import com.example.shopsecure.entity.OrderTrackingAccessToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderTrackingAccessTokenRepository
        extends JpaRepository<OrderTrackingAccessToken, Long> {

    Optional<OrderTrackingAccessToken> findByTokenHash(String tokenHash);

    Optional<OrderTrackingAccessToken> findByOrder_Id(Long orderId);

    boolean existsByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from OrderTrackingAccessToken t where t.order.id = :orderId")
    Optional<OrderTrackingAccessToken> findByOrderIdForUpdate(@Param("orderId") Long orderId);
}
