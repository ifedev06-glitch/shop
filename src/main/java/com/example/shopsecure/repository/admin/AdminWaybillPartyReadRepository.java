package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.WaybillParty;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface AdminWaybillPartyReadRepository extends Repository<WaybillParty, Long> {

    @EntityGraph(attributePaths = "user")
    List<WaybillParty> findByWaybill_IdOrderByRoleAsc(Long waybillId, Pageable pageable);
}
