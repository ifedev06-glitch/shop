package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.OrderWalletPaymentResponse;
import com.example.shopsecure.dto.response.PaymentInstructionsResponse;
import com.example.shopsecure.dto.response.VirtualAccountDetailsResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.event.OrderFundedEvent;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class OrderWalletFundingService {

    private final OrderRepository orderRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final WalletTransactionRepository transactionRepository;
    private final PaymentReceiptRepository receiptRepository;
    private final WalletService walletService;
    private final OrderService orderService;
    private final OrderStatusService statusService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public OrderWalletPaymentResponse pay(Long orderId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        Order snapshot = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        orderService.requireOwner(snapshot);
        VirtualAccount account = virtualAccountRepository.findByUserIdForUpdate(snapshot.getCreator().getId())
                .orElseThrow(() -> new BusinessException("Wallet top-up virtual account is not available"));
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        orderService.requireOwner(order);
        requireWalletFunding(order);

        WalletTransaction existing = transactionRepository
                .findByOrder_IdAndTransactionType(orderId, WalletTransactionType.ORDER_PAYMENT_DEBIT)
                .orElse(null);
        if (existing != null) {
            if (!existing.getIdempotencyKey().equals(idempotencyKey)) {
                throw new BusinessException("A different idempotency key was already used for this wallet payment");
            }
            return fundedResponse(order, existing.getBalanceAfter(), existing.getCreatedAt());
        }

        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT
                || order.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new BusinessException("Order is not awaiting wallet payment");
        }
        if (order.getTotalAmount() == null || order.getTotalAmount().signum() <= 0) {
            throw new BusinessException("Order total is not available");
        }
        if (receiptRepository.existsByOrder_IdAndProviderStatus(
                orderId,
                PaymentTransactionStatus.SUCCESS
        )) {
            throw new BusinessException("Order already has a direct payment receipt and requires manual review");
        }

        WalletService.WalletDebitResult debit = walletService.debitOrderPayment(order, idempotencyKey);
        if (!debit.funded()) {
            return new OrderWalletPaymentResponse(
                    order.getId(),
                    WalletPaymentOutcome.INSUFFICIENT_FUNDS,
                    order.getStatus(),
                    order.getPaymentStatus(),
                    order.getTotalAmount(),
                    debit.balance(),
                    debit.shortfall(),
                    order.getCurrency(),
                    topUpInstructions(order, account, debit.shortfall()),
                    null
            );
        }

        Instant fundedAt = debit.occurredAt();
        order.setPaymentStatus(PaymentStatus.CONFIRMED);
        order.setFundedAt(fundedAt);
        statusService.transition(order, OrderStatus.SHIPMENT_BOOKING, "Order paid from buyer wallet");
        orderRepository.save(order);
        eventPublisher.publishEvent(new OrderFundedEvent(order.getId()));
        return fundedResponse(order, debit.balance(), fundedAt);
    }

    private void requireWalletFunding(Order order) {
        if (order.getCreatorRole() != CreatorRole.BUYER
                || order.getFundingMode() != OrderFundingMode.WALLET) {
            throw new AccessDeniedException("This order is not payable from the creator wallet");
        }
    }

    private OrderWalletPaymentResponse fundedResponse(
            Order order,
            BigDecimal balance,
            Instant fundedAt
    ) {
        return new OrderWalletPaymentResponse(
                order.getId(),
                WalletPaymentOutcome.FUNDED,
                order.getStatus(),
                PaymentStatus.CONFIRMED,
                order.getTotalAmount(),
                balance,
                BigDecimal.ZERO.setScale(2),
                order.getCurrency(),
                null,
                fundedAt
        );
    }

    private PaymentInstructionsResponse topUpInstructions(
            Order order,
            VirtualAccount account,
            BigDecimal shortfall
    ) {
        return new PaymentInstructionsResponse(
                new VirtualAccountDetailsResponse(
                        account.getAccountNumber(),
                        account.getBankName(),
                        account.getAccountName(),
                        account.getExpiresAt()
                ),
                shortfall,
                order.getCurrency(),
                order.getPaymentReference(),
                "SHOPSECURE WALLET TOPUP " + order.getPaymentReference()
        );
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new BusinessException("A valid Idempotency-Key header is required");
        }
    }
}
