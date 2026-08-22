package com.example.shopsecure.repository;

import com.example.shopsecure.entity.CreatorRole;
import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.OrderFundingMode;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.PaymentStatus;
import com.example.shopsecure.entity.SettlementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByShareToken(String shareToken);

    boolean existsByShareToken(String shareToken);

    Page<Order> findByCreator_Id(Long creatorId, Pageable pageable);

    @Query("select coalesce(sum(o.itemAmount), 0) as pendingAmount, "
            + "coalesce(sum(case when o.paymentStatus = :fundedStatus then o.itemAmount else 0 end), 0) "
            + "as fundedPendingAmount, count(o) as activeOrderCount, "
            + "coalesce(sum(case when o.paymentStatus = :fundedStatus then 1 else 0 end), 0) "
            + "as fundedOrderCount "
            + "from Order o left join OrderCounterparty c on c.order = o "
            + "where ((o.creator.id = :userId and o.creatorRole = :sellerRole) "
            + "or (c.user.id = :userId and o.creatorRole = :buyerRole)) "
            + "and o.status not in :closedStatuses "
            + "and o.settlementStatus <> :completedSettlement")
    SellerPendingAmountProjection summarizeSellerPendingAmount(
            @Param("userId") Long userId,
            @Param("sellerRole") CreatorRole sellerRole,
            @Param("buyerRole") CreatorRole buyerRole,
            @Param("closedStatuses") Collection<OrderStatus> closedStatuses,
            @Param("completedSettlement") SettlementStatus completedSettlement,
            @Param("fundedStatus") PaymentStatus fundedStatus
    );

    List<Order> findByCreator_IdAndStatus(Long creatorId, OrderStatus status);

    interface SellerPendingAmountProjection {
        BigDecimal getPendingAmount();

        BigDecimal getFundedPendingAmount();

        Long getActiveOrderCount();

        Long getFundedOrderCount();
    }

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
