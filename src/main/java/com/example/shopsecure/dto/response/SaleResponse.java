package com.example.shopsecure.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@AllArgsConstructor
public class SaleResponse {

    private Long id;
    private String saleNumber;
    private BigDecimal totalAmount;
    private BigDecimal discount;
    private String paymentMethod;
    private String customerName;
    private String customerPhone;
    private String notes;
    private LocalDateTime createdAt;
    private List<ItemResponse> items;

    @Getter
    @Builder
    @AllArgsConstructor
    public static class ItemResponse {
        private Long id;
        private Long productId;
        private String productName;
        private Integer quantity;
        private BigDecimal sellingPrice;
        private BigDecimal costPrice;
        private BigDecimal subtotal;
    }
}
