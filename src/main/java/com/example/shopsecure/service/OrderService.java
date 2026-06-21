package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.OrderResponse;
import com.example.shopsecure.entity.Business;
import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.BusinessRepository;
import com.example.shopsecure.repository.OrderRepository;
import com.example.shopsecure.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    public Page<OrderResponse> getOrderHistory(int page) {

        Business business = getCurrentBusiness();

        Pageable pageable = PageRequest.of(page, 10, Sort.by("createdAt").descending());

        return orderRepository
                .findOrdersByBusinessId(business.getId(), pageable)
                .map(this::toResponse);
    }

    private Business getCurrentBusiness() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found")); // ← changed

        return businessRepository.findByOwner_Id(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found")); // ← changed
    }

    private OrderResponse toResponse(Order order) {
        return OrderResponse.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .customerName(order.getCustomerName())
                .email(order.getEmail())
                .phoneNumber(order.getPhoneNumber())
                .deliveryAddress(order.getDeliveryAddress())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .title(order.getTitle())
                .description(order.getDescription())
                .createdAt(order.getCreatedAt())
                .build();
    }
}