package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateBusinessRequest {

    @NotBlank
    private String businessName;

//    @NotBlank
//    private String storefrontSlug;

    private String description;

    private String phoneNumber;

    private String address;
}