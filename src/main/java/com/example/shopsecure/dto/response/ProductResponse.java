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
public class ProductResponse {

    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private BigDecimal costPrice;
    private Integer stock;
    private Integer lowStockThreshold;
    private List<String> imageUrls;
    private String categoryName;
    private String businessName;
    private LocalDateTime createdAt;
}