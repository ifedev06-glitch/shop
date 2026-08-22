package com.example.shopsecure.repository;

import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WaybillRepository extends JpaRepository<Waybill, Long> {

    Optional<Waybill> findByPublicId(String publicId);

    Optional<Waybill> findByPublicIdAndCreator_Id(String publicId, Long creatorId);

    Optional<Waybill> findByCreator_IdAndCreationIdempotencyKey(
            Long creatorId,
            String creationIdempotencyKey
    );

    Page<Waybill> findByCreator_Id(Long creatorId, Pageable pageable);

    @Query("select w.id from Waybill w where w.status = :status "
            + "and (not exists (select s.id from WaybillShipment s where s.waybill = w) "
            + "or exists (select s.id from WaybillShipment s where s.waybill = w "
            + "and (s.status not in :dispatchableStatuses "
            + "or s.nextCancellationAttemptAt is null "
            + "or s.nextCancellationAttemptAt <= :now))) "
            + "order by w.cancellationRequestedAt, w.id")
    List<Long> findWaybillIdsDueForCancellation(
            @Param("status") WaybillStatus status,
            @Param("dispatchableStatuses") Collection<ShipmentStatus> dispatchableStatuses,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Waybill w where w.id = :id")
    Optional<Waybill> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Waybill w where w.publicId = :publicId")
    Optional<Waybill> findByPublicIdForUpdate(@Param("publicId") String publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Waybill w "
            + "where w.creator.id = :creatorId and w.creationIdempotencyKey = :idempotencyKey")
    Optional<Waybill> findByCreatorAndCreationIdempotencyKeyForUpdate(
            @Param("creatorId") Long creatorId,
            @Param("idempotencyKey") String idempotencyKey
    );
}
