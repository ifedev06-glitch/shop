package com.example.shopsecure.repository;

import com.example.shopsecure.entity.WaybillInvitation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WaybillInvitationRepository extends JpaRepository<WaybillInvitation, Long> {

    @Query("select i from WaybillInvitation i join fetch i.waybill where i.tokenHash = :tokenHash")
    Optional<WaybillInvitation> findByTokenHash(@Param("tokenHash") String tokenHash);

    @Query("select i from WaybillInvitation i join fetch i.waybill "
            + "where i.accessTokenHash = :accessTokenHash")
    Optional<WaybillInvitation> findByAccessTokenHash(@Param("accessTokenHash") String accessTokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from WaybillInvitation i join fetch i.waybill where i.tokenHash = :tokenHash")
    Optional<WaybillInvitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from WaybillInvitation i join fetch i.waybill "
            + "where i.accessTokenHash = :accessTokenHash")
    Optional<WaybillInvitation> findByAccessTokenHashForUpdate(
            @Param("accessTokenHash") String accessTokenHash
    );

    @Query("select i from WaybillInvitation i "
            + "where i.waybill.id = :waybillId and i.revokedAt is null and i.expiresAt > :now "
            + "order by i.createdAt, i.id")
    List<WaybillInvitation> findActiveByWaybillId(
            @Param("waybillId") Long waybillId,
            @Param("now") LocalDateTime now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from WaybillInvitation i "
            + "where i.waybill.id = :waybillId and i.revokedAt is null and i.expiresAt > :now "
            + "order by i.createdAt, i.id")
    List<WaybillInvitation> findActiveByWaybillIdForUpdate(
            @Param("waybillId") Long waybillId,
            @Param("now") LocalDateTime now
    );
}
