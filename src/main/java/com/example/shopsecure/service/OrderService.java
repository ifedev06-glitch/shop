package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.CreateOrderRequest;
import com.example.shopsecure.dto.request.VendorInfoRequest;
import com.example.shopsecure.dto.response.CreateOrderResponse;
import com.example.shopsecure.dto.response.OrderDetailsResponse;
import com.example.shopsecure.dto.response.OrderStatusHistoryResponse;
import com.example.shopsecure.dto.response.OrderVerificationResponse;
import com.example.shopsecure.dto.response.PublicOrderResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.event.VendorInfoCompletedEvent;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.OtpVerificationException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.OrderCounterpartyRepository;
import com.example.shopsecure.repository.OrderRepository;
import com.example.shopsecure.repository.OrderStatusHistoryRepository;
import com.example.shopsecure.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderCounterpartyRepository counterpartyRepository;
    private final UserRepository userRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final OtpService otpService;
    private final WhatsAppBotService whatsAppBotService;
    private final ApplicationEventPublisher eventPublisher;
    private final OrderStatusService orderStatusService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.base-url}")
    private String appBaseUrl;

    @Transactional
    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        User creator = getCurrentUser();
        String shareToken = generateUniqueShareToken();
        Instant now = Instant.now();
        Order order = orderRepository.save(Order.builder()
                .amount(request.amount())
                .itemDescription(request.itemDescription().trim())
                .buyerPhoneNumber(request.buyerPhoneNumber())
                .buyerEmail(request.buyerEmail().trim().toLowerCase())
                .deliveryAddressLine1(request.deliveryAddress().line1().trim())
                .deliveryCity(request.deliveryAddress().city().trim())
                .deliveryState(request.deliveryAddress().state().trim())
                .deliveryCountry(request.deliveryAddress().country().trim())
                .creatorRole(CreatorRole.BUYER)
                .status(OrderStatus.CREATED)
                .shareToken(shareToken)
                .creator(creator)
                .createdAt(LocalDateTime.now())
                .updatedAt(now)
                .build());
        orderStatusService.recordInitial(order, "Order created by buyer");

        return new CreateOrderResponse(
                order.getId(),
                order.getAmount(),
                order.getItemDescription(),
                order.getCreatorRole(),
                order.getStatus(),
                order.getShareToken(),
                publicOrderLink(order.getShareToken()),
                order.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public PublicOrderResponse getByShareToken(String shareToken) {
        Order order = findByShareToken(shareToken);
        return toPublicResponse(order);
    }

    @Transactional(readOnly = true)
    public OrderDetailsResponse getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        requireOwner(order);
        return new OrderDetailsResponse(
                order.getId(),
                order.getItemDescription(),
                order.getAmount(),
                order.getCurrency(),
                order.getBuyerPhoneNumber(),
                order.getBuyerEmail(),
                order.getDeliveryAddressLine1(),
                order.getDeliveryCity(),
                order.getDeliveryState(),
                order.getDeliveryCountry(),
                order.getStatus(),
                order.getPaidAt(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<OrderStatusHistoryResponse> getHistory(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        requireOwner(order);
        return historyRepository.findByOrder_IdOrderByCreatedAtAsc(orderId).stream()
                .map(history -> new OrderStatusHistoryResponse(
                        history.getFromStatus(),
                        history.getToStatus(),
                        history.getReason(),
                        history.getCreatedAt()
                ))
                .toList();
    }

    @Transactional
    public OrderVerificationResponse submitVendorInfo(String shareToken, VendorInfoRequest request) {
        Order order = findByShareTokenForUpdate(shareToken);
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new BusinessException("Vendor information can no longer be changed");
        }

        OrderCounterparty counterparty = counterpartyRepository.findByOrder_Id(order.getId())
                .orElseGet(() -> OrderCounterparty.builder()
                        .order(order)
                        .createdAt(LocalDateTime.now())
                         .build());
        counterparty.setPhoneNumber(request.phoneNumber());
        counterparty.setEmail(request.email().trim().toLowerCase());
        counterparty.setAddressLine1(request.address().line1().trim());
        counterparty.setCity(request.address().city().trim());
        counterparty.setState(request.address().state().trim());
        counterparty.setCountry(request.address().country().trim());
        counterparty.setBankAccountNumber(request.bankAccount().accountNumber());
        counterparty.setBankName(request.bankAccount().bankName().trim());
        counterparty.setPhoneVerified(false);
        counterpartyRepository.save(counterparty);

        String code = otpService.issue(
                OtpPurpose.ORDER_COUNTERPARTY,
                order.getShareToken(),
                counterparty.getPhoneNumber()
        );
        whatsAppBotService.sendOtp(counterparty.getPhoneNumber(), code);

        order.setUpdatedAt(Instant.now());
        orderRepository.save(order);
        return new OrderVerificationResponse(order.getStatus(), false);
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public OrderVerificationResponse verifyVendorOtp(String shareToken, String code) {
        Order order = findByShareTokenForUpdate(shareToken);
        OrderCounterparty counterparty = counterpartyRepository.findByOrder_Id(order.getId())
                .orElseThrow(() -> new BusinessException("Vendor information must be submitted first"));

        if (order.getStatus() != OrderStatus.CREATED
                && counterparty.isPhoneVerified()) {
            return new OrderVerificationResponse(order.getStatus(), true);
        }
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new BusinessException("Order is not awaiting vendor verification");
        }

        otpService.verify(OtpPurpose.ORDER_COUNTERPARTY, order.getShareToken(), code);
        counterparty.setPhoneVerified(true);
        counterpartyRepository.save(counterparty);

        orderStatusService.transition(
                order,
                OrderStatus.VENDOR_INFO_COMPLETE,
                "Vendor phone and payout details verified"
        );
        orderRepository.save(order);
        eventPublisher.publishEvent(new VendorInfoCompletedEvent(
                order.getBuyerEmail(),
                order.getCreator().getFirstname(),
                order.getItemDescription()
        ));

        return new OrderVerificationResponse(order.getStatus(), true);
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private void requireOwner(Order order) {
        User currentUser = getCurrentUser();
        if (!order.getCreator().getId().equals(currentUser.getId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Order does not belong to current user"
            );
        }
    }

    private Order findByShareToken(String shareToken) {
        return orderRepository.findByShareToken(shareToken)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    private Order findByShareTokenForUpdate(String shareToken) {
        return orderRepository.findByShareTokenForUpdate(shareToken)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    private PublicOrderResponse toPublicResponse(Order order) {
        return new PublicOrderResponse(
                order.getAmount(),
                order.getItemDescription(),
                order.getCurrency(),
                order.getStatus()
        );
    }

    private String generateUniqueShareToken() {
        for (int attempt = 0; attempt < 5; attempt++) {
            byte[] bytes = new byte[32];
            secureRandom.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (!orderRepository.existsByShareToken(token)) {
                return token;
            }
        }
        throw new BusinessException("Unable to create a secure share link");
    }

    private String publicOrderLink(String shareToken) {
        return appBaseUrl.replaceAll("/+$", "") + "/orders/token/" + shareToken;
    }

}
