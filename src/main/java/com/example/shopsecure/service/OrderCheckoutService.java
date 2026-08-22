package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.OrderCheckoutResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.PaymentException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.provider.payment.PaymentInitResponse;
import com.example.shopsecure.provider.payment.PaymentProvider;
import com.example.shopsecure.provider.payment.PaymentRequest;
import com.example.shopsecure.provider.payment.PaymentStatusUpdatedEvent;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import com.example.shopsecure.provider.payment.PaymentVerificationResult;
import com.example.shopsecure.repository.OrderPaymentAttemptRepository;
import com.example.shopsecure.repository.OrderRepository;
import com.example.shopsecure.repository.ShippingQuoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderCheckoutService {

    private final GuestOrderAccessService guestAccessService;
    private final OrderRepository orderRepository;
    private final OrderPaymentAttemptRepository attemptRepository;
    private final ShippingQuoteRepository quoteRepository;
    private final PaymentProvider paymentProvider;
    private final OrderPaymentService paymentService;

    @Value("${app.base-url}")
    private String appBaseUrl;

    @Value("${payment.checkout.recheck-after:PT15M}")
    private Duration recheckAfter;

    @Transactional
    public OrderCheckoutResponse initialize(String shareToken, String guestToken) {
        GuestOrderAccessToken access = guestAccessService.require(shareToken, guestToken);
        Order order = orderRepository.findByIdForUpdate(access.getOrder().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        requireGuestCheckout(order, access);
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT
                || order.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new BusinessException("Order is not awaiting checkout payment");
        }
        requireActiveSelectedQuote(order);

        OrderPaymentAttempt existing = attemptRepository
                .findFirstByOrder_IdAndStatusOrderByCreatedAtDesc(
                        order.getId(),
                        PaymentAttemptStatus.INITIALIZED
                )
                .orElse(null);
        if (existing != null) {
            Instant now = Instant.now();
            if (existing.getUpdatedAt().plus(recheckAfter).isAfter(now)) {
                return response(order, existing);
            }
            PaymentVerificationResult verification;
            try {
                verification = paymentProvider.verifyTransaction(existing.getProviderReference());
            } catch (PaymentException exception) {
                existing.setUpdatedAt(now);
                attemptRepository.save(existing);
                return response(order, existing);
            }
            if (!existing.getProviderReference().equals(verification.reference())) {
                throw new PaymentException("Payment provider returned a mismatched checkout reference");
            }
            if (verification.status() == PaymentTransactionStatus.SUCCESS) {
                if (verification.eventId() == null || verification.eventId().isBlank()
                        || verification.amount() == null
                        || verification.currency() == null || verification.currency().isBlank()) {
                    throw new PaymentException("Invalid payment verification response");
                }
                paymentService.handleEvent(new PaymentStatusUpdatedEvent(
                        verification.eventId(),
                        verification.reference(),
                        verification.status(),
                        verification.amount(),
                        verification.currency(),
                        verification.channel(),
                        null,
                        verification.occurredAt(),
                        true
                ));
                return response(order, existing);
            }
            if (verification.status() != PaymentTransactionStatus.FAILED
                    && verification.status() != PaymentTransactionStatus.ABANDONED) {
                existing.setUpdatedAt(now);
                attemptRepository.save(existing);
                return response(order, existing);
            }
            existing.setStatus(verification.status() == PaymentTransactionStatus.ABANDONED
                    ? PaymentAttemptStatus.ABANDONED
                    : PaymentAttemptStatus.FAILED);
            existing.setUpdatedAt(now);
            existing.setCompletedAt(now);
            attemptRepository.save(existing);
        }
        if (attemptRepository.findFirstByOrder_IdAndStatusOrderByCreatedAtDesc(
                order.getId(),
                PaymentAttemptStatus.SUCCESS
        ).isPresent()) {
            throw new BusinessException("A successful checkout payment requires manual review");
        }
        if (order.getTotalAmount() == null || order.getTotalAmount().signum() <= 0) {
            throw new BusinessException("Order total is not available");
        }

        String reference = checkoutReference(order.getId());
        PaymentInitResponse initialized = paymentProvider.initializeTransaction(new PaymentRequest(
                order.getTotalAmount(),
                access.getCounterparty().getEmail(),
                reference,
                order.getCurrency(),
                callbackUrl(shareToken),
                Map.of("order_id", order.getId(), "funding_mode", order.getFundingMode().name())
        ));
        if (!reference.equals(initialized.reference())) {
            throw new PaymentException("Payment provider returned a mismatched checkout reference");
        }

        Instant now = Instant.now();
        OrderPaymentAttempt attempt = attemptRepository.save(OrderPaymentAttempt.builder()
                .order(order)
                .provider("PAYSTACK")
                .providerReference(reference)
                .authorizationUrl(initialized.checkoutUrl())
                .accessCode(initialized.accessCode())
                .customerEmail(access.getCounterparty().getEmail())
                .amount(order.getTotalAmount())
                .currency(order.getCurrency())
                .status(PaymentAttemptStatus.INITIALIZED)
                .createdAt(now)
                .updatedAt(now)
                .build());
        return response(order, attempt);
    }

    private void requireGuestCheckout(Order order, GuestOrderAccessToken access) {
        if (!order.getId().equals(access.getOrder().getId())
                || !access.getCounterparty().isPhoneVerified()
                || order.getCreatorRole() != CreatorRole.VENDOR
                || order.getFundingMode() != OrderFundingMode.PAYSTACK_CHECKOUT) {
            throw new AccessDeniedException("This order is not payable through guest checkout");
        }
    }

    private void requireActiveSelectedQuote(Order order) {
        ShippingQuote quote = quoteRepository.findByShipment_Order_IdAndStatus(
                        order.getId(),
                        ShippingQuoteStatus.SELECTED
                )
                .orElseThrow(() -> new BusinessException("Selected shipping quote is not available"));
        if (!quote.getExpiresAt().isAfter(Instant.now())) {
            throw new BusinessException("Selected shipping quote has expired; creator must request fresh rates");
        }
    }

    private OrderCheckoutResponse response(Order order, OrderPaymentAttempt attempt) {
        return new OrderCheckoutResponse(
                order.getId(),
                order.getFundingMode(),
                order.getStatus(),
                order.getPaymentStatus(),
                attempt.getAmount(),
                attempt.getCurrency(),
                attempt.getProviderReference(),
                attempt.getAuthorizationUrl(),
                attempt.getAccessCode(),
                attempt.getCreatedAt()
        );
    }

    private String checkoutReference(Long orderId) {
        return "checkout-order-" + orderId + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String callbackUrl(String shareToken) {
        return appBaseUrl.replaceAll("/+$", "") + "/orders/token/" + shareToken;
    }
}
