package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.WaybillPartyRole;

public record WaybillPublicPartyResponse(
        WaybillPartyRole role,
        String nameMasked,
        String emailMasked,
        String phoneNumberMasked,
        String city,
        String state,
        String country
) {
}
