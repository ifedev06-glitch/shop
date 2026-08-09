package com.example.shopsecure.service;

import com.example.shopsecure.entity.*;
import com.example.shopsecure.event.OrderFundedEvent;
import com.example.shopsecure.provider.payment.PaymentProvider;
import com.example.shopsecure.provider.payment.PaymentStatusUpdatedEvent;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import com.example.shopsecure.provider.common.WebhookPayload;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderPaymentService {

    private static final EnumSet<PaymentReceiptClassification> COUNTED_RECEIPTS = EnumSet.of(
            PaymentReceiptClassification.UNDERPAID,
            PaymentReceiptClassification.MATCHED,
            PaymentReceiptClassification.OVERPAID
    );

    private final PaymentProvider paymentProvider;
    private final PaymentReceiptRepository receiptRepository;
    private final OrderPaymentAttemptRepository attemptRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final OrderRepository orderRepository;
    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final OrderStatusService statusService;
    private final ApplicationEventPublisher eventPublisher;

    public boolean isPaymentEvent(WebhookPayload payload) {
        return paymentProvider.handleWebhookEvent(payload).relevant();
    }

    @Transactional
    public void handleWebhook(WebhookPayload payload) {
        PaymentStatusUpdatedEvent event = paymentProvider.handleWebhookEvent(payload);
        handleEvent(event);
    }

    @Transactional
    public void handleEvent(PaymentStatusUpdatedEvent event) {
        if (!event.relevant()) {
            return;
        }
        String eventKey = eventKey(event);
        if (receiptRepository.existsByProviderEventKey(eventKey)) {
            return;
        }

        OrderPaymentAttempt checkoutCandidate = attemptRepository
                .findByProviderReference(event.reference())
                .orElse(null);
        if (checkoutCandidate != null) {
            orderRepository.findByIdForUpdate(checkoutCandidate.getOrder().getId())
                    .orElseThrow(() -> new IllegalStateException("Checkout order not found"));
            OrderPaymentAttempt attempt = attemptRepository
                    .findByProviderReferenceForUpdate(event.reference())
                    .orElseThrow(() -> new IllegalStateException("Checkout attempt not found"));
            if (!receiptRepository.existsByProviderEventKey(eventKey)) {
                applyCheckoutEvent(attempt, event, eventKey);
            }
            return;
        }

        VirtualAccount account = event.receiverAccountNumber() == null
                ? null
                : virtualAccountRepository.findByAccountNumberForUpdate(event.receiverAccountNumber()).orElse(null);
        if (account != null && receiptRepository.existsByProviderEventKey(eventKey)) {
            return;
        }
        Order order = null;
        PaymentReceiptClassification classification = PaymentReceiptClassification.UNMATCHED;
        if (account != null) {
            List<Order> candidates = orderRepository.findByCreatorAndFundingModeAndStatusForUpdate(
                    account.getUser().getId(),
                    OrderFundingMode.DIRECT_DVA,
                    OrderStatus.AWAITING_PAYMENT
            );
            if (candidates.size() == 1) {
                order = candidates.get(0);
                classification = classifyDirectPayment(order, event);
                applyDirectPayment(order, event, classification);
            } else if (candidates.isEmpty()) {
                classification = classifyWalletCredit(account, event);
            }
        }

        PaymentReceipt receipt = receiptRepository.save(PaymentReceipt.builder()
                .order(order)
                .providerEventKey(eventKey)
                .providerReference(event.reference())
                .receiverAccountNumber(event.receiverAccountNumber())
                .amount(event.amount())
                .currency(event.currency())
                .channel(event.channel())
                .providerStatus(event.status())
                .classification(classification)
                .occurredAt(event.occurredAt())
                .createdAt(Instant.now())
                .build());
        if (classification == PaymentReceiptClassification.WALLET_CREDITED) {
            walletService.creditDvaTransfer(account.getUser(), receipt);
        }
        if (order != null) {
            orderRepository.save(order);
            if (classification == PaymentReceiptClassification.MATCHED) {
                eventPublisher.publishEvent(new OrderFundedEvent(order.getId()));
            }
        }
    }

    private void applyCheckoutEvent(
            OrderPaymentAttempt attempt,
            PaymentStatusUpdatedEvent event,
            String eventKey
    ) {
        Order order = attempt.getOrder();
        PaymentReceiptClassification classification = classifyCheckout(order, attempt, event);
        Instant now = Instant.now();

        if (event.status() == PaymentTransactionStatus.SUCCESS) {
            attempt.setStatus(PaymentAttemptStatus.SUCCESS);
            attempt.setCompletedAt(event.occurredAt() == null ? now : event.occurredAt());
        } else if (attempt.getStatus() != PaymentAttemptStatus.SUCCESS) {
            attempt.setStatus(event.status() == PaymentTransactionStatus.ABANDONED
                    ? PaymentAttemptStatus.ABANDONED
                    : PaymentAttemptStatus.FAILED);
        }
        attempt.setUpdatedAt(now);
        attemptRepository.save(attempt);

        receiptRepository.save(receipt(order, event, eventKey, classification));
        boolean funded = applyCheckoutPayment(order, event, classification);
        if (funded) {
            attemptRepository.findByOrder_IdAndStatus(
                    order.getId(),
                    PaymentAttemptStatus.INITIALIZED
            ).stream()
                    .filter(other -> !other.getId().equals(attempt.getId()))
                    .forEach(other -> {
                        other.setStatus(PaymentAttemptStatus.SUPERSEDED);
                        other.setUpdatedAt(now);
                        attemptRepository.save(other);
                    });
        }
        if (funded || classification == PaymentReceiptClassification.UNDERPAID
                || classification == PaymentReceiptClassification.OVERPAID) {
            orderRepository.save(order);
        }
        if (funded) {
            eventPublisher.publishEvent(new OrderFundedEvent(order.getId()));
        }
    }

    private PaymentReceiptClassification classifyCheckout(
            Order order,
            OrderPaymentAttempt attempt,
            PaymentStatusUpdatedEvent event
    ) {
        if (event.status() != PaymentTransactionStatus.SUCCESS) {
            return PaymentReceiptClassification.FAILED;
        }
        if (order.getFundingMode() != OrderFundingMode.PAYSTACK_CHECKOUT
                || order.getStatus() != OrderStatus.AWAITING_PAYMENT
                || order.getPaymentStatus() == PaymentStatus.CONFIRMED
                || order.getFundedAt() != null) {
            return PaymentReceiptClassification.DUPLICATE;
        }
        if (event.currency() == null
                || !attempt.getCurrency().equalsIgnoreCase(event.currency())) {
            return PaymentReceiptClassification.UNMATCHED;
        }
        if (event.amount() == null || event.amount().signum() <= 0) {
            return PaymentReceiptClassification.UNMATCHED;
        }
        int comparison = event.amount().compareTo(attempt.getAmount());
        if (comparison < 0) {
            return PaymentReceiptClassification.UNDERPAID;
        }
        if (comparison > 0) {
            return PaymentReceiptClassification.OVERPAID;
        }
        return PaymentReceiptClassification.MATCHED;
    }

    private boolean applyCheckoutPayment(
            Order order,
            PaymentStatusUpdatedEvent event,
            PaymentReceiptClassification classification
    ) {
        switch (classification) {
            case MATCHED -> {
                order.setPaymentStatus(PaymentStatus.CONFIRMED);
                order.setFundedAt(event.occurredAt() == null ? Instant.now() : event.occurredAt());
                statusService.transition(
                        order,
                        OrderStatus.SHIPMENT_BOOKING,
                        "Paystack checkout payment confirmed"
                );
                return true;
            }
            case UNDERPAID -> order.setPaymentStatus(PaymentStatus.UNDERPAID);
            case OVERPAID -> order.setPaymentStatus(PaymentStatus.OVERPAID);
            case DUPLICATE, WALLET_CREDITED, FAILED, UNMATCHED -> {
            }
        }
        order.setUpdatedAt(Instant.now());
        return false;
    }

    private PaymentReceiptClassification classifyDirectPayment(
            Order order,
            PaymentStatusUpdatedEvent event
    ) {
        if (event.status() != PaymentTransactionStatus.SUCCESS) {
            return PaymentReceiptClassification.FAILED;
        }
        if (!"dedicated_nuban".equalsIgnoreCase(event.channel())
                && !"bank_transfer".equalsIgnoreCase(event.channel())) {
            return PaymentReceiptClassification.UNMATCHED;
        }
        if (!order.getCurrency().equalsIgnoreCase(event.currency())) {
            return PaymentReceiptClassification.UNMATCHED;
        }
        if (event.amount() == null || event.amount().signum() <= 0) {
            return PaymentReceiptClassification.UNMATCHED;
        }
        java.math.BigDecimal alreadyReceived = receiptRepository.sumAmountByOrderAndClassificationIn(
                order.getId(),
                COUNTED_RECEIPTS
        );
        int comparison = alreadyReceived.add(event.amount()).compareTo(order.getTotalAmount());
        if (comparison < 0) {
            return PaymentReceiptClassification.UNDERPAID;
        }
        if (comparison > 0) {
            return PaymentReceiptClassification.OVERPAID;
        }
        return PaymentReceiptClassification.MATCHED;
    }

    private PaymentReceiptClassification classifyWalletCredit(
            VirtualAccount account,
            PaymentStatusUpdatedEvent event
    ) {
        if (event.status() != PaymentTransactionStatus.SUCCESS) {
            return PaymentReceiptClassification.FAILED;
        }
        if (!"dedicated_nuban".equalsIgnoreCase(event.channel())
                && !"bank_transfer".equalsIgnoreCase(event.channel())) {
            return PaymentReceiptClassification.UNMATCHED;
        }
        if (event.amount() == null || event.amount().signum() <= 0) {
            return PaymentReceiptClassification.UNMATCHED;
        }
        Wallet wallet = walletRepository.findByUser_Id(account.getUser().getId()).orElse(null);
        if (wallet == null || event.currency() == null
                || !wallet.getCurrency().equalsIgnoreCase(event.currency())) {
            return PaymentReceiptClassification.UNMATCHED;
        }
        return PaymentReceiptClassification.WALLET_CREDITED;
    }

    private void applyDirectPayment(
            Order order,
            PaymentStatusUpdatedEvent event,
            PaymentReceiptClassification classification
    ) {
        switch (classification) {
            case MATCHED -> {
                order.setPaymentStatus(PaymentStatus.CONFIRMED);
                order.setFundedAt(event.occurredAt() == null ? Instant.now() : event.occurredAt());
                statusService.transition(
                        order,
                        OrderStatus.SHIPMENT_BOOKING,
                        "Exact virtual-account payment confirmed"
                );
            }
            case UNDERPAID -> order.setPaymentStatus(PaymentStatus.UNDERPAID);
            case OVERPAID -> order.setPaymentStatus(PaymentStatus.OVERPAID);
            case FAILED -> {
                if (order.getPaymentStatus() == PaymentStatus.PENDING
                        || order.getPaymentStatus() == PaymentStatus.NOT_STARTED) {
                    order.setPaymentStatus(PaymentStatus.FAILED);
                }
            }
            case DUPLICATE, WALLET_CREDITED, UNMATCHED -> {
            }
        }
        order.setUpdatedAt(Instant.now());
    }

    private PaymentReceipt receipt(
            Order order,
            PaymentStatusUpdatedEvent event,
            String eventKey,
            PaymentReceiptClassification classification
    ) {
        return PaymentReceipt.builder()
                .order(order)
                .providerEventKey(eventKey)
                .providerReference(event.reference())
                .receiverAccountNumber(event.receiverAccountNumber())
                .amount(event.amount())
                .currency(event.currency())
                .channel(event.channel())
                .providerStatus(event.status())
                .classification(classification)
                .occurredAt(event.occurredAt())
                .createdAt(Instant.now())
                .build();
    }

    private String eventKey(PaymentStatusUpdatedEvent event) {
        if (event.eventId() == null || event.eventId().isBlank()) {
            return event.reference() + ":" + event.status();
        }
        return event.status() == PaymentTransactionStatus.SUCCESS
                ? event.eventId()
                : event.eventId() + ":" + event.status();
    }
}
