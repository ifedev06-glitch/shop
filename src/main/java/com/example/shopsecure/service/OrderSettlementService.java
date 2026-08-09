package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.DeliveryConfirmationResponse;
import com.example.shopsecure.dto.response.OtpChallengeResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.event.VendorSettlementCompletedEvent;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.OtpVerificationException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.provider.payment.*;
import com.example.shopsecure.repository.*;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderSettlementService {

    private final OrderRepository orderRepository;
    private final OrderCounterpartyRepository counterpartyRepository;
    private final OrderSettlementRepository settlementRepository;
    private final OrderShipmentRepository shipmentRepository;
    private final PayoutProvider payoutProvider;
    private final WalletService walletService;
    private final OrderStatusService statusService;
    private final OrderService orderService;
    private final GuestOrderAccessService guestAccessService;
    private final OtpService otpService;
    private final OtpDeliveryService otpDeliveryService;
    private final PlatformTransactionManager transactionManager;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${payout.max-attempts:3}")
    private int maxPayoutAttempts;

    @Value("${payout.reconciliation.batch-size:50}")
    private int payoutReconciliationBatchSize;

    public DeliveryConfirmationResponse confirmCreatorDelivery(
            Long orderId,
            String idempotencyKey
    ) {
        validateIdempotencyKey(idempotencyKey);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        PreparedPayout prepared = transaction.execute(status -> preparePayout(orderId, idempotencyKey));
        if (prepared == null || !prepared.dispatch()) {
            return prepared == null ? null : prepared.response();
        }

        PayoutResponse providerResponse;
        try {
            providerResponse = payoutProvider.initiatePayout(new PayoutRequest(
                    prepared.recipientCode(),
                    prepared.amount(),
                    prepared.reference(),
                    "ShopSecure order " + prepared.orderId() + " seller settlement"
            ));
        } catch (RuntimeException exception) {
            return transaction.execute(status -> markDispatchUnknown(prepared.orderId()));
        }
        if (!prepared.reference().equals(providerResponse.reference())) {
            return transaction.execute(status -> markDispatchUnknown(prepared.orderId()));
        }
        return transaction.execute(status -> applyInitiation(prepared.orderId(), providerResponse));
    }

    @Scheduled(
            initialDelayString = "${payout.reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${payout.reconciliation.delay-ms:60000}"
    )
    public void reconcileAutomatedPayouts() {
        settlementRepository.findBySettlementTypeAndStatusIn(
                SettlementType.EXTERNAL_PAYOUT,
                java.util.List.of(
                        SettlementStatus.PENDING,
                        SettlementStatus.DISPATCH_UNKNOWN,
                        SettlementStatus.REQUIRES_ACTION,
                        SettlementStatus.FAILED
                ),
                PageRequest.of(0, Math.max(1, payoutReconciliationBatchSize))
        ).forEach(settlement -> {
            try {
                reconcilePayout(settlement);
            } catch (RuntimeException exception) {
                log.error("Unable to reconcile payout: settlementId={}", settlement.getId());
            }
        });
    }

    @Transactional
    public OtpChallengeResponse requestGuestDeliveryOtp(String shareToken, String guestToken) {
        GuestOrderAccessToken access = guestAccessService.require(shareToken, guestToken);
        Order order = orderRepository.findByIdForUpdate(access.getOrder().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (order.getCreatorRole() != CreatorRole.VENDOR) {
            throw new BusinessException("The authenticated buyer confirms this order without guest OTP");
        }
        if (order.getStatus() != OrderStatus.READY_FOR_DELIVERY) {
            throw new BusinessException("Order is not ready for delivery confirmation");
        }
        OrderCounterparty counterparty = access.getCounterparty();
        OtpService.IssuedOtp issued = otpService.issue(
                OtpPurpose.DELIVERY_CONFIRMATION,
                deliverySubject(order),
                counterparty.getPhoneNumber()
        );
        otpDeliveryService.send(
                counterparty.getPhoneNumber(),
                counterparty.getEmail(),
                issued.code(),
                OtpPurpose.DELIVERY_CONFIRMATION
        );
        return new OtpChallengeResponse(
                issued.challengeId(),
                CreatorRole.BUYER,
                maskPhone(counterparty.getPhoneNumber()),
                issued.expiresInSeconds()
        );
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public DeliveryConfirmationResponse confirmGuestDelivery(
            String shareToken,
            String guestToken,
            String challengeId,
            String code,
            String idempotencyKey
    ) {
        validateIdempotencyKey(idempotencyKey);
        GuestOrderAccessToken access = guestAccessService.require(shareToken, guestToken);
        Order order = orderRepository.findByIdForUpdate(access.getOrder().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (order.getCreatorRole() != CreatorRole.VENDOR) {
            throw new BusinessException("Only a guest buyer can confirm this order through the share link");
        }
        OrderSettlement existing = settlementRepository.findByOrderIdForUpdate(order.getId()).orElse(null);
        if (existing != null) {
            requireMatchingKey(existing, idempotencyKey);
            return response(order);
        }
        if (order.getStatus() != OrderStatus.READY_FOR_DELIVERY
                || order.getPaymentStatus() != PaymentStatus.CONFIRMED) {
            throw new BusinessException("Order is not ready for delivery confirmation");
        }
        requireActiveShipment(order);
        otpService.verify(
                OtpPurpose.DELIVERY_CONFIRMATION,
                deliverySubject(order),
                challengeId,
                code
        );
        walletService.creditOrderRelease(order, idempotencyKey);
        Instant now = Instant.now();
        settlementRepository.save(OrderSettlement.builder()
                .order(order)
                .settlementType(SettlementType.WALLET_CREDIT)
                .amount(order.getItemAmount())
                .status(SettlementStatus.COMPLETED)
                .idempotencyKey(idempotencyKey)
                .payoutReference("wallet_order_" + order.getId() + "_release")
                .createdAt(now)
                .updatedAt(now)
                .completedAt(now)
                .build());
        order.setDeliveryConfirmedAt(now);
        order.setCompletedAt(now);
        order.setSettlementStatus(SettlementStatus.COMPLETED);
        statusService.transition(order, OrderStatus.COMPLETED, "Buyer confirmed delivery; vendor wallet credited");
        orderRepository.save(order);
        publishVendorSettlementCompleted(order, SettlementType.WALLET_CREDIT);
        return response(order);
    }

    @Transactional
    public void applyPayoutStatus(PayoutStatusUpdatedEvent event) {
        if (!event.relevant()) {
            return;
        }
        OrderSettlement settlement = settlementRepository
                .findByPayoutReferenceForUpdate(event.reference())
                .orElse(null);
        if (settlement == null) {
            return;
        }
        Order order = orderRepository.findByIdForUpdate(settlement.getOrder().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (settlement.getStatus() == SettlementStatus.REVERSED) {
            return;
        }
        if (event.status() != PayoutStatus.REVERSED
                && event.occurredAt() != null
                && settlement.getProviderUpdatedAt() != null
                && event.occurredAt().isBefore(settlement.getProviderUpdatedAt())) {
            return;
        }
        setTransferId(settlement, event.transferId());
        applyPayoutState(order, settlement, event.status());
        Instant providerUpdatedAt = event.occurredAt() == null ? Instant.now() : event.occurredAt();
        settlement.setProviderUpdatedAt(providerUpdatedAt);
        settlement.setUpdatedAt(providerUpdatedAt);
        settlementRepository.save(settlement);
        orderRepository.save(order);
    }

    private PreparedPayout preparePayout(Long orderId, String idempotencyKey) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        orderService.requireOwner(order);
        if (order.getCreatorRole() != CreatorRole.BUYER) {
            throw new BusinessException("Guest buyer confirmation is required for vendor-created orders");
        }
        OrderSettlement existing = settlementRepository.findByOrderIdForUpdate(orderId).orElse(null);
        if (existing != null) {
            requireMatchingKey(existing, idempotencyKey);
            return new PreparedPayout(
                    orderId,
                    null,
                    existing.getAmount(),
                    existing.getPayoutReference(),
                    false,
                    response(order)
            );
        }
        if (order.getStatus() != OrderStatus.READY_FOR_DELIVERY
                || order.getPaymentStatus() != PaymentStatus.CONFIRMED) {
            throw new BusinessException("Order is not ready for delivery confirmation");
        }
        requireActiveShipment(order);
        OrderCounterparty vendor = counterpartyRepository.findByOrder_Id(orderId)
                .filter(OrderCounterparty::isPhoneVerified)
                .orElseThrow(() -> new BusinessException("Verified vendor payout details were not found"));
        if (vendor.getPayoutRecipientCode() == null || vendor.getPayoutRecipientCode().isBlank()) {
            throw new BusinessException("Vendor payout recipient is not available");
        }
        Instant now = Instant.now();
        String reference = "shopsecure_order_" + orderId + "_payout";
        settlementRepository.save(OrderSettlement.builder()
                .order(order)
                .settlementType(SettlementType.EXTERNAL_PAYOUT)
                .amount(order.getItemAmount())
                .status(SettlementStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .payoutReference(reference)
                .payoutAttempt(1)
                .createdAt(now)
                .updatedAt(now)
                .build());
        order.setDeliveryConfirmedAt(now);
        order.setSettlementStatus(SettlementStatus.PENDING);
        statusService.transition(order, OrderStatus.PAYOUT_PENDING, "Buyer confirmed delivery; payout queued");
        orderRepository.save(order);
        return new PreparedPayout(
                orderId,
                vendor.getPayoutRecipientCode(),
                order.getItemAmount(),
                reference,
                true,
                response(order)
        );
    }

    private DeliveryConfirmationResponse applyInitiation(Long orderId, PayoutResponse response) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        OrderSettlement settlement = settlementRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order settlement not found"));
        if (settlement.getStatus() == SettlementStatus.COMPLETED
                || settlement.getStatus() == SettlementStatus.REVERSED) {
            return response(order);
        }
        setTransferId(settlement, response.transferId());
        applyPayoutState(order, settlement, response.status());
        settlement.setUpdatedAt(Instant.now());
        settlementRepository.save(settlement);
        orderRepository.save(order);
        return response(order);
    }

    private DeliveryConfirmationResponse markDispatchUnknown(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        OrderSettlement settlement = settlementRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order settlement not found"));
        if (settlement.getStatus() == SettlementStatus.COMPLETED
                || settlement.getStatus() == SettlementStatus.REVERSED) {
            return response(order);
        }
        settlement.setStatus(SettlementStatus.DISPATCH_UNKNOWN);
        settlement.setFailureReason("Payout dispatch outcome requires provider verification");
        settlement.setUpdatedAt(Instant.now());
        order.setSettlementStatus(SettlementStatus.DISPATCH_UNKNOWN);
        settlementRepository.save(settlement);
        orderRepository.save(order);
        return response(order);
    }

    private void applyPayoutState(
            Order order,
            OrderSettlement settlement,
            PayoutStatus payoutStatus
    ) {
        switch (payoutStatus) {
            case SUCCESS -> {
                if (settlement.getStatus() == SettlementStatus.COMPLETED
                        || settlement.getStatus() == SettlementStatus.REVERSED) {
                    return;
                }
                Instant now = Instant.now();
                settlement.setStatus(SettlementStatus.COMPLETED);
                settlement.setCompletedAt(now);
                order.setSettlementStatus(SettlementStatus.COMPLETED);
                order.setCompletedAt(now);
                if (order.getStatus() == OrderStatus.PAYOUT_PENDING
                        || order.getStatus() == OrderStatus.PAYOUT_FAILED) {
                    statusService.transition(order, OrderStatus.COMPLETED, "Vendor payout confirmed");
                }
                publishVendorSettlementCompleted(order, SettlementType.EXTERNAL_PAYOUT);
            }
            case REQUIRES_ACTION -> {
                if (settlement.getStatus() == SettlementStatus.COMPLETED
                        || settlement.getStatus() == SettlementStatus.REVERSED) {
                    return;
                }
                settlement.setStatus(SettlementStatus.REQUIRES_ACTION);
                order.setSettlementStatus(SettlementStatus.REQUIRES_ACTION);
            }
            case FAILED -> {
                if (settlement.getStatus() != SettlementStatus.COMPLETED
                        && settlement.getStatus() != SettlementStatus.REVERSED) {
                    markFailed(order, settlement, SettlementStatus.FAILED, "Vendor payout failed");
                }
            }
            case REVERSED -> markFailed(order, settlement, SettlementStatus.REVERSED, "Vendor payout reversed");
            case PENDING, UNKNOWN -> {
                if (settlement.getStatus() != SettlementStatus.COMPLETED
                        && settlement.getStatus() != SettlementStatus.REVERSED
                        && settlement.getStatus() != SettlementStatus.FAILED) {
                    settlement.setStatus(SettlementStatus.PENDING);
                    order.setSettlementStatus(SettlementStatus.PENDING);
                }
            }
        }
    }

    private void reconcilePayout(OrderSettlement settlement) {
        PayoutStatusUpdatedEvent verified;
        try {
            verified = payoutProvider.verifyPayout(settlement.getPayoutReference());
        } catch (RuntimeException ignored) {
            verified = null;
        }
        if (verified != null) {
            PayoutStatusUpdatedEvent verifiedEvent = verified;
            requiresNewTransaction().executeWithoutResult(status -> applyPayoutStatus(verifiedEvent));
            if (verified.status() == PayoutStatus.FAILED) {
                retryFailedPayout(settlement.getOrder().getId());
            }
            return;
        }
        if (settlement.getTransferId() != null
                || settlement.getStatus() == SettlementStatus.REQUIRES_ACTION
                || settlement.getStatus() == SettlementStatus.FAILED) {
            return;
        }
        OrderCounterparty vendor = counterpartyRepository.findByOrder_Id(settlement.getOrder().getId())
                .orElse(null);
        if (vendor == null || vendor.getPayoutRecipientCode() == null) {
            return;
        }
        try {
            PayoutResponse response = payoutProvider.initiatePayout(new PayoutRequest(
                    vendor.getPayoutRecipientCode(),
                    settlement.getAmount(),
                    settlement.getPayoutReference(),
                    "ShopSecure order " + settlement.getOrder().getId() + " seller settlement"
            ));
            requiresNewTransaction().executeWithoutResult(status ->
                    applyRecoveredInitiation(settlement.getOrder().getId(), response)
            );
        } catch (RuntimeException ignored) {
            // Leave the deterministic reference for the next verification cycle.
        }
    }

    private void retryFailedPayout(Long orderId) {
        PreparedPayout retry = requiresNewTransaction().execute(status -> preparePayoutRetry(orderId));
        if (retry == null || !retry.dispatch()) {
            return;
        }
        try {
            PayoutResponse response = payoutProvider.initiatePayout(new PayoutRequest(
                    retry.recipientCode(),
                    retry.amount(),
                    retry.reference(),
                    "ShopSecure order " + retry.orderId() + " seller settlement retry"
            ));
            if (!retry.reference().equals(response.reference())) {
                requiresNewTransaction().executeWithoutResult(status -> markDispatchUnknown(retry.orderId()));
                return;
            }
            requiresNewTransaction().executeWithoutResult(status -> applyRecoveredInitiation(
                    retry.orderId(),
                    response
            ));
        } catch (RuntimeException ignored) {
            requiresNewTransaction().executeWithoutResult(status -> markDispatchUnknown(retry.orderId()));
        }
    }

    private PreparedPayout preparePayoutRetry(Long orderId) {
        OrderSettlement settlement = settlementRepository.findByOrderIdForUpdate(orderId).orElse(null);
        if (settlement == null || settlement.getStatus() != SettlementStatus.FAILED) {
            return null;
        }
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            return null;
        }
        if (settlement.getPayoutAttempt() >= maxPayoutAttempts) {
            settlement.setStatus(SettlementStatus.REQUIRES_ACTION);
            settlement.setFailureReason("Automated payout retry limit reached");
            settlement.setUpdatedAt(Instant.now());
            order.setSettlementStatus(SettlementStatus.REQUIRES_ACTION);
            settlementRepository.save(settlement);
            orderRepository.save(order);
            return null;
        }
        OrderCounterparty vendor = counterpartyRepository.findByOrder_Id(orderId).orElse(null);
        if (vendor == null || vendor.getPayoutRecipientCode() == null
                || vendor.getPayoutRecipientCode().isBlank()) {
            settlement.setStatus(SettlementStatus.REQUIRES_ACTION);
            settlement.setFailureReason("Vendor payout recipient is unavailable");
            settlement.setUpdatedAt(Instant.now());
            order.setSettlementStatus(SettlementStatus.REQUIRES_ACTION);
            settlementRepository.save(settlement);
            orderRepository.save(order);
            return null;
        }

        int attempt = settlement.getPayoutAttempt() + 1;
        String reference = "shopsecure_order_" + orderId + "_payout_" + attempt;
        settlement.setPayoutAttempt(attempt);
        settlement.setPayoutReference(reference);
        settlement.setTransferId(null);
        settlement.setStatus(SettlementStatus.PENDING);
        settlement.setFailureReason(null);
        settlement.setProviderUpdatedAt(null);
        settlement.setUpdatedAt(Instant.now());
        order.setSettlementStatus(SettlementStatus.PENDING);
        if (order.getStatus() == OrderStatus.PAYOUT_FAILED) {
            statusService.transition(order, OrderStatus.PAYOUT_PENDING, "Retrying vendor payout");
        }
        settlementRepository.save(settlement);
        orderRepository.save(order);
        return new PreparedPayout(
                orderId,
                vendor.getPayoutRecipientCode(),
                settlement.getAmount(),
                reference,
                true,
                response(order)
        );
    }

    @Transactional
    public void applyRecoveredInitiation(Long orderId, PayoutResponse response) {
        if (response.reference() == null) {
            return;
        }
        OrderSettlement settlement = settlementRepository.findByOrderIdForUpdate(orderId).orElse(null);
        if (settlement == null || !settlement.getPayoutReference().equals(response.reference())) {
            return;
        }
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            return;
        }
        setTransferId(settlement, response.transferId());
        applyPayoutState(order, settlement, response.status());
        settlement.setUpdatedAt(Instant.now());
        settlementRepository.save(settlement);
        orderRepository.save(order);
    }

    private void setTransferId(OrderSettlement settlement, String transferId) {
        if (transferId == null || transferId.isBlank()) {
            return;
        }
        if (settlement.getTransferId() != null && !settlement.getTransferId().equals(transferId)) {
            throw new BusinessException("Payout transfer reference does not match the existing settlement");
        }
        settlement.setTransferId(transferId);
    }

    private TransactionTemplate requiresNewTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private void markFailed(
            Order order,
            OrderSettlement settlement,
            SettlementStatus settlementStatus,
            String reason
    ) {
        settlement.setStatus(settlementStatus);
        settlement.setFailureReason(reason);
        order.setSettlementStatus(settlementStatus);
        if (order.getStatus() == OrderStatus.PAYOUT_PENDING || order.getStatus() == OrderStatus.COMPLETED) {
            statusService.transition(order, OrderStatus.PAYOUT_FAILED, reason);
        }
    }

    private void publishVendorSettlementCompleted(Order order, SettlementType settlementType) {
        eventPublisher.publishEvent(new VendorSettlementCompletedEvent(
                order.getId(),
                settlementType
        ));
    }

    private void requireMatchingKey(OrderSettlement settlement, String key) {
        if (!settlement.getIdempotencyKey().equals(key)) {
            throw new BusinessException("A different idempotency key was already used for delivery confirmation");
        }
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new BusinessException("A valid Idempotency-Key header is required");
        }
    }

    private String deliverySubject(Order order) {
        return "delivery:" + order.getShareToken();
    }

    private void requireActiveShipment(Order order) {
        ShipmentStatus status = shipmentRepository.findByOrder_Id(order.getId())
                .map(OrderShipment::getStatus)
                .orElseThrow(() -> new BusinessException("Order shipment was not found"));
        if (status == ShipmentStatus.CANCELLED
                || status == ShipmentStatus.FAILED
                || status == ShipmentStatus.DRAFT
                || status == ShipmentStatus.UNKNOWN) {
            throw new BusinessException("Shipment is not eligible for delivery confirmation");
        }
    }

    private DeliveryConfirmationResponse response(Order order) {
        return new DeliveryConfirmationResponse(
                order.getStatus(),
                order.getSettlementStatus(),
                order.getDeliveryConfirmedAt(),
                order.getCompletedAt()
        );
    }

    private String maskPhone(String phone) {
        if (phone.length() <= 4) {
            return "****";
        }
        return phone.substring(0, Math.min(5, phone.length() - 2))
                + "*".repeat(Math.max(4, phone.length() - 7))
                + phone.substring(phone.length() - 2);
    }

    private record PreparedPayout(
            Long orderId,
            String recipientCode,
            java.math.BigDecimal amount,
            String reference,
            boolean dispatch,
            DeliveryConfirmationResponse response
    ) {
    }
}
