package com.example.shopsecure.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class WhatsAppLinkResponse {

    private String phoneNumber;
}
