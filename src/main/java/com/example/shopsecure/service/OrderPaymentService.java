package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.WalletPaymentResponse;
import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.OrderRepository;
import com.example.shopsecure.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class OrderPaymentService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final WalletService walletService;
    private final OrderStatusService orderStatusService;

    @Transactional
    public WalletPaymentResponse pay(Long orderId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        User buyer = currentUser();
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getCreator().getId().equals(buyer.getId())) {
            throw new AccessDeniedException("Order does not belong to current user");
        }

        if (order.getStatus() == OrderStatus.PAID_ESCROW) {
            return response(order, walletService.getOrderMovement(order.getId()));
        }
        if (order.getStatus() != OrderStatus.VENDOR_INFO_COMPLETE) {
            throw new BusinessException("Vendor must accept the order before payment");
        }
        if (!hasCompleteBuyerDetails(order)) {
            throw new BusinessException("Buyer contact and delivery details must be completed before payment");
        }

        WalletService.WalletMovement movement = walletService.moveToProcessing(
                buyer.getId(),
                order,
                order.getAmount(),
                idempotencyKey
        );
        order.setPaymentIdempotencyKey(idempotencyKey);
        order.setPaidAt(Instant.now());
        orderStatusService.transition(
                order,
                OrderStatus.PAID_ESCROW,
                "Buyer wallet balance moved into escrow"
        );
        orderRepository.save(order);
        return response(order, movement);
    }

    private User currentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new BusinessException("A valid Idempotency-Key header is required");
        }
    }

    private boolean hasCompleteBuyerDetails(Order order) {
        return hasText(order.getBuyerPhoneNumber())
                && hasText(order.getBuyerEmail())
                && hasText(order.getDeliveryAddressLine1())
                && hasText(order.getDeliveryCity())
                && hasText(order.getDeliveryState())
                && hasText(order.getDeliveryCountry());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private WalletPaymentResponse response(Order order, WalletService.WalletMovement movement) {
        return new WalletPaymentResponse(
                order.getId(),
                order.getStatus(),
                movement.amount(),
                movement.availableBalance(),
                movement.processingBalance(),
                movement.currency(),
                order.getPaidAt()
        );
    }
}
