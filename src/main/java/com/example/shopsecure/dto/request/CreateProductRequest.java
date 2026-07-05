package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CreateProductRequest {

    @NotBlank(message = "Product name is required")
    @Size(max = 200, message = "Product name cannot exceed 200 characters")
    private String name;

    @NotBlank(message = "Description is required")
    @Size(max = 2000, message = "Description cannot exceed 2000 characters")
    private String description;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "1.00", message = "Price must be at least ₦1")
    private BigDecimal price;

    @NotNull(message = "Cost price is required")
    @DecimalMin(value = "0.00", message = "Cost price cannot be negative")
    private BigDecimal costPrice;

    @NotNull(message = "Stock is required")
    @Min(value = 0, message = "Stock cannot be negative")
    private Integer stock;

    private Integer lowStockThreshold;

    @NotNull(message = "Category is required")
    private Long categoryId;
}