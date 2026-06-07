package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VerifyPinRequest {
    @NotBlank
    @Size(min = 6, max = 6, message = "PIN must be exactly 6 digits")
    private String pin;
}
