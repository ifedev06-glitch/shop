package com.example.shopsecure.dto.request;

import com.example.shopsecure.entity.OrderStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateOrderStatusRequest {

    private OrderStatus status;
}
