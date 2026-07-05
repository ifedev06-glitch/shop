package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LinkWhatsAppRequest {

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\d{7,15}$", message = "Phone number must be 7-15 digits (country code + number)")
    private String phoneNumber;
}
