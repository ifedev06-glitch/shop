package com.example.shopsecure.repository;

import com.example.shopsecure.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.OrderFundingMode;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByShareToken(String shareToken);

    boolean existsByShareToken(String shareToken);

    List<Order> findByCreator_IdOrderByCreatedAtDesc(Long creatorId);

    List<Order> findByCreator_IdAndStatus(Long creatorId, OrderStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.shareToken = :shareToken")
    Optional<Order> findByShareTokenForUpdate(@Param("shareToken") String shareToken);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.creator.id = :creatorId and o.status = :status")
    List<Order> findByCreatorAndStatusForUpdate(
            @Param("creatorId") Long creatorId,
            @Param("status") OrderStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.creator.id = :creatorId "
            + "and o.fundingMode = :fundingMode and o.status = :status order by o.id")
    List<Order> findByCreatorAndFundingModeAndStatusForUpdate(
            @Param("creatorId") Long creatorId,
            @Param("fundingMode") OrderFundingMode fundingMode,
            @Param("status") OrderStatus status
    );

}
