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
public class ReceiptResponse {

    private String receiptNumber;
    private String saleNumber;
    private LocalDateTime createdAt;

    private String businessName;
    private String businessPhone;
    private String businessAddress;
    private String logoUrl;

    private String customerName;
    private String customerPhone;

    private List<Item> items;
    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal total;
    private String paymentMethod;

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Item {
        private String productName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal total;
    }
}
