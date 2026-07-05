package com.example.shopsecure.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
public class BusinessResponse {

    private Long id;
    private String businessName;
    private String storefrontSlug;
    private String description;
    private String phoneNumber;
    private String address;
    private LocalDateTime createdAt;
    private String storefrontUrl;
    private String logoUrl;
}