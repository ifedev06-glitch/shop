package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.OrderStatus;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@Builder
public class OrderResponse {

    private Long orderId;

    private String orderNumber;

    private String customerName;

    private String email;

    private String phoneNumber;

    private String deliveryAddress;

    private BigDecimal totalAmount;

    private OrderStatus status;

    private List<OrderItemResponse> items;

    private LocalDateTime createdAt;

    private String title;
    private String description;
}
