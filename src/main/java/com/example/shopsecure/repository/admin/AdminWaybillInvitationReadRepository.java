package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.WaybillInvitation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface AdminWaybillInvitationReadRepository extends Repository<WaybillInvitation, Long> {
    List<WaybillInvitation> findByWaybill_IdOrderByCreatedAtDesc(Long waybillId, Pageable pageable);
}
