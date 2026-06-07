package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePinRequest {
    @NotBlank
    private String oldPin;

    @NotBlank
    @Size(min = 6, max = 6, message = "PIN must be exactly 6 digits")
    @Pattern(regexp = "\\d{6}", message = "PIN must contain only digits")
    private String newPin;

    @NotBlank
    private String confirmNewPin;
}