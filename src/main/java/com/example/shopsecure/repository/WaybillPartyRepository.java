package com.example.shopsecure.repository;

import com.example.shopsecure.entity.WaybillParty;
import com.example.shopsecure.entity.WaybillPartyRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WaybillPartyRepository extends JpaRepository<WaybillParty, Long> {

    Optional<WaybillParty> findByWaybill_IdAndRole(Long waybillId, WaybillPartyRole role);

    List<WaybillParty> findByWaybill_IdOrderByRoleAsc(Long waybillId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from WaybillParty p where p.waybill.id = :waybillId and p.role = :role")
    Optional<WaybillParty> findByWaybillIdAndRoleForUpdate(
            @Param("waybillId") Long waybillId,
            @Param("role") WaybillPartyRole role
    );
}
