package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.WaybillPartyRole;
import com.example.shopsecure.entity.WaybillPartySource;

import java.time.LocalDateTime;

public record WaybillPartyResponse(
        WaybillPartyRole role,
        WaybillPartySource source,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        AddressResponse address,
        boolean accountLinked,
        LocalDateTime detailsCompletedAt,
        LocalDateTime contactVerifiedAt
) {
}
