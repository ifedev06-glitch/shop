package com.example.shopsecure.repository;

import com.example.shopsecure.entity.WaybillParcel;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WaybillParcelRepository extends JpaRepository<WaybillParcel, Long> {

    Optional<WaybillParcel> findByWaybill_Id(Long waybillId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from WaybillParcel p where p.waybill.id = :waybillId")
    Optional<WaybillParcel> findByWaybillIdForUpdate(@Param("waybillId") Long waybillId);
}
