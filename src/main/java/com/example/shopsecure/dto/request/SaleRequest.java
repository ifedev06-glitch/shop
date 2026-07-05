package com.example.shopsecure.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class SaleRequest {

    private BigDecimal discount;

    private String paymentMethod;

    private String customerName;

    private String customerPhone;

    private String notes;

    @NotNull(message = "Items are required")
    @Size(min = 1, message = "At least one item is required")
    private List<Item> items;

    @Getter
    @Setter
    public static class Item {

        @NotNull(message = "Product ID is required")
        private Long productId;

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        private Integer quantity;
    }
}
