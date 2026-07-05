package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class CreateCheckoutLinkRequest {

    @Size(max = 200, message = "Title cannot exceed 200 characters")
    private String title;

    @Size(max = 1000, message = "Description cannot exceed 1000 characters")
    private String description;

    @DecimalMin(value = "1.00", message = "Amount must be at least ₦1")
    private BigDecimal amount;

    private BigDecimal subtotalAmount;

    private BigDecimal discount;

    private List<CheckoutLinkProductRequest> items;
}