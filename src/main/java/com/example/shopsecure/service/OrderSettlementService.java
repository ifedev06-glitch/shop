package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.DeliveryConfirmationResponse;
import com.example.shopsecure.dto.response.OtpChallengeResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.event.OrderCarrierPickupConfirmedEvent;
import com.example.shopsecure.event.VendorSettlementCompletedEvent;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.OtpVerificationException;
import com.example.shopsecure.exception.PayoutRejectedException;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderSettlementService {

    private static final String PAYOUT_CURRENCY = "NGN";

    private final OrderRepository orderRepository;
    private final OrderCounterpartyRepository counterpartyRepository;
    private final OrderSettlementRepository settlementRepository;
    private final WalletWithdrawalRepository withdrawalRepository;
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

    @Value("${payout.reconciliation.batch-size:50}")
    private int payoutReconciliationBatchSize;

    @Value("${payout.reconciliation.retry-delay:PT1M}")
    private Duration payoutReconciliationRetryDelay;

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
        return dispatchPayout(prepared, transaction);
    }

    @Scheduled(
            initialDelayString = "${payout.reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${payout.reconciliation.delay-ms:60000}"
    )
    public void reconcileAutomatedPayouts() {
        settlementRepository.findDueForReconciliation(
                SettlementType.EXTERNAL_PAYOUT,
                java.util.List.of(
                        SettlementStatus.PENDING,
                        SettlementStatus.DISPATCH_UNKNOWN
                ),
                Instant.now(),
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
        requireBuyerDeliveryConfirmationPolicy(order);
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

    public DeliveryConfirmationResponse confirmGuestDelivery(
            String shareToken,
            String guestToken,
            String challengeId,
            String code,
            String idempotencyKey
    ) {
        validateIdempotencyKey(idempotencyKey);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        GuestPreparation preparation = transaction.execute(status -> {
            try {
                return new GuestPreparation(
                        prepareGuestPayout(shareToken, guestToken, challengeId, code, idempotencyKey),
                        null
                );
            } catch (OtpVerificationException exception) {
                // Commit the failed-attempt counter before returning the verification error.
                return new GuestPreparation(null, exception);
            }
        });
        if (preparation == null) {
            throw new IllegalStateException("Guest delivery confirmation did not produce a result");
        }
        if (preparation.verificationFailure() != null) {
            throw preparation.verificationFailure();
        }
        PreparedPayout prepared = preparation.payout();
        if (prepared == null || !prepared.dispatch()) {
            return prepared == null ? null : prepared.response();
        }
        return dispatchPayout(prepared, transaction);
    }

    private PreparedPayout prepareGuestPayout(
            String shareToken,
            String guestToken,
            String challengeId,
            String code,
            String idempotencyKey
    ) {
        GuestOrderAccessToken access = guestAccessService.require(shareToken, guestToken);
        Order order = orderRepository.findByIdForUpdate(access.getOrder().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        requireBuyerDeliveryConfirmationPolicy(order);
        if (order.getCreatorRole() != CreatorRole.VENDOR) {
            throw new BusinessException("Only a guest buyer can confirm this order through the share link");
        }
        OrderSettlement existing = settlementRepository.findByOrderIdForUpdate(order.getId()).orElse(null);
        if (existing != null) {
            requireMatchingKey(existing, idempotencyKey);
            return new PreparedPayout(
                    order.getId(),
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
        otpService.verify(
                OtpPurpose.DELIVERY_CONFIRMATION,
                deliverySubject(order),
                challengeId,
                code
        );
        String recipientCode = vendorRecipientCode(order);
        if (recipientCode == null) {
            return new PreparedPayout(
                    order.getId(),
                    null,
                    order.getItemAmount(),
                    null,
                    false,
                    completeLegacyWalletSettlement(order, idempotencyKey)
            );
        }
        return prepareExternalPayout(order, idempotencyKey, recipientCode);
    }

    @Transactional
    public void applyPayoutStatus(PayoutStatusUpdatedEvent event) {
        if (!event.relevant()) {
            return;
        }
        java.util.Optional<OrderSettlement> byReference = event.reference() == null
                ? java.util.Optional.empty()
                : settlementRepository.findByPayoutReference(event.reference());
        java.util.Optional<OrderSettlement> byTransfer = event.transferId() == null
                || event.transferId().isBlank()
                ? java.util.Optional.empty()
                : settlementRepository.findByTransferId(event.transferId());
        java.util.Optional<WalletWithdrawal> withdrawalByReference = event.reference() == null
                ? java.util.Optional.empty()
                : withdrawalRepository.findByReference(event.reference());
        java.util.Optional<WalletWithdrawal> withdrawalByTransfer = event.transferId() == null
                || event.transferId().isBlank()
                ? java.util.Optional.empty()
                : withdrawalRepository.findByTransferId(event.transferId());
        if (byTransfer.isEmpty() && withdrawalByTransfer.isPresent()) {
            return;
        }
        if (byTransfer.isEmpty() && byReference.isEmpty() && withdrawalByReference.isPresent()) {
            return;
        }
        OrderSettlement snapshot = byTransfer.or(() -> byReference).orElse(null);
        if (snapshot == null) {
            return;
        }
        boolean crossDomainCollision = (byTransfer.isPresent() && withdrawalByTransfer.isPresent())
                || (byReference.isPresent() && withdrawalByReference.isPresent());
        Order order = orderRepository.findByIdForUpdate(snapshot.getOrder().getId()).orElse(null);
        OrderSettlement settlement = settlementRepository.findByIdForUpdate(snapshot.getId()).orElse(null);
        if (order == null || settlement == null
                || !order.getId().equals(settlement.getOrder().getId())) {
            return;
        }
        if (crossDomainCollision) {
            recordPayoutMismatchLocked(
                    order,
                    settlement,
                    event.reference(),
                    event.transferId(),
                    "Provider payout identity is assigned to multiple payouts; manual reconciliation is required"
            );
            return;
        }
        if (!settlement.getPayoutReference().equals(event.reference())) {
            recordPayoutMismatchLocked(
                    order,
                    settlement,
                    event.reference(),
                    event.transferId(),
                    "Provider webhook returned a mismatched payout reference; manual reconciliation is required"
            );
            return;
        }
        applyPayoutStatusLocked(order, settlement, event);
    }

    private void applyPayoutStatusLocked(
            Order order,
            OrderSettlement settlement,
            PayoutStatusUpdatedEvent event
    ) {
        if (settlement.getStatus() == SettlementStatus.REVERSED) {
            return;
        }
        String ownershipMismatch = payoutTransferOwnershipMismatch(settlement, event.transferId());
        if (ownershipMismatch != null) {
            recordPayoutMismatchLocked(
                    order,
                    settlement,
                    event.reference(),
                    event.transferId(),
                    ownershipMismatch
            );
            return;
        }
        String mismatch = payoutEventMismatch(order, settlement, event);
        if (mismatch != null) {
            recordPayoutMismatchLocked(order, settlement, event.reference(), event.transferId(), mismatch);
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
        requireBuyerDeliveryConfirmationPolicy(order);
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
        String recipientCode = vendorRecipientCode(order);
        if (recipientCode == null) {
            throw new BusinessException("Vendor payout recipient is not available");
        }
        return prepareExternalPayout(order, idempotencyKey, recipientCode);
    }

    private PreparedPayout prepareExternalPayout(
            Order order,
            String idempotencyKey,
            String recipientCode
    ) {
        Instant now = Instant.now();
        String reference = "shopsecure_order_" + order.getId() + "_payout";
        settlementRepository.save(OrderSettlement.builder()
                .order(order)
                .settlementType(SettlementType.EXTERNAL_PAYOUT)
                .amount(order.getItemAmount())
                .status(SettlementStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .trigger(SettlementTrigger.BUYER_DELIVERY_CONFIRMATION)
                .triggeredAt(now)
                .payoutReference(reference)
                .payoutAttempt(1)
                .nextReconciliationAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
        order.setDeliveryConfirmedAt(now);
        order.setSettlementStatus(SettlementStatus.PENDING);
        statusService.transition(order, OrderStatus.PAYOUT_PENDING, "Buyer confirmed delivery; payout queued");
        orderRepository.save(order);
        return new PreparedPayout(
                order.getId(),
                recipientCode,
                order.getItemAmount(),
                reference,
                true,
                response(order)
        );
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
                settlement.setNextReconciliationAt(null);
                settlement.setReconciliationToken(null);
                order.setSettlementStatus(SettlementStatus.COMPLETED);
                boolean carrierPickup = settlement.getTrigger() == SettlementTrigger.CARRIER_PICKUP;
                boolean carrierDelivered = shipmentRepository.findByOrder_Id(order.getId())
                        .map(OrderShipment::getStatus)
                        .filter(status -> status == ShipmentStatus.DELIVERED)
                        .isPresent();
                if (carrierPickup && !carrierDelivered) {
                    if (order.getStatus() == OrderStatus.PAYOUT_PENDING
                            || order.getStatus() == OrderStatus.PAYOUT_FAILED) {
                        statusService.transition(
                                order,
                                OrderStatus.IN_TRANSIT,
                                "Vendor payout confirmed while carrier delivery continues"
                        );
                    }
                } else {
                    order.setCompletedAt(now);
                    if (order.getStatus() == OrderStatus.PAYOUT_PENDING
                            || order.getStatus() == OrderStatus.PAYOUT_FAILED) {
                        statusService.transition(order, OrderStatus.COMPLETED, "Vendor payout confirmed");
                    }
                }
                publishVendorSettlementCompleted(
                        order,
                        SettlementType.EXTERNAL_PAYOUT,
                        settlement.getTrigger()
                );
            }
            case REQUIRES_ACTION -> {
                if (settlement.getStatus() == SettlementStatus.COMPLETED
                        || settlement.getStatus() == SettlementStatus.REVERSED) {
                    return;
                }
                settlement.setStatus(SettlementStatus.REQUIRES_ACTION);
                settlement.setNextReconciliationAt(null);
                settlement.setReconciliationToken(null);
                order.setSettlementStatus(SettlementStatus.REQUIRES_ACTION);
            }
            case FAILED -> {
                if (settlement.getStatus() != SettlementStatus.COMPLETED
                        && settlement.getStatus() != SettlementStatus.REVERSED) {
                    settlement.setStatus(SettlementStatus.REQUIRES_ACTION);
                    settlement.setFailureReason("Vendor payout failed; manual review is required");
                    settlement.setNextReconciliationAt(null);
                    settlement.setReconciliationToken(null);
                    order.setSettlementStatus(SettlementStatus.REQUIRES_ACTION);
                }
            }
            case REVERSED -> markFailed(order, settlement, SettlementStatus.REVERSED, "Vendor payout reversed");
            case PENDING, UNKNOWN -> {
                if (settlement.getStatus() != SettlementStatus.COMPLETED
                        && settlement.getStatus() != SettlementStatus.REVERSED
                        && settlement.getStatus() != SettlementStatus.FAILED) {
                    settlement.setStatus(SettlementStatus.PENDING);
                    settlement.setNextReconciliationAt(nextPayoutReconciliationAt(Instant.now()));
                    settlement.setReconciliationToken(null);
                    order.setSettlementStatus(SettlementStatus.PENDING);
                }
            }
        }
    }

    private void reconcilePayout(OrderSettlement settlement) {
        PayoutReconciliation claimed = requiresNewTransaction().execute(status ->
                claimPayoutReconciliation(settlement.getId(), settlement.getOrder().getId())
        );
        if (claimed == null) {
            return;
        }
        PayoutStatusUpdatedEvent verified;
        try {
            verified = payoutProvider.verifyPayout(claimed.reference());
        } catch (RuntimeException ignored) {
            verified = null;
        }
        if (verified != null) {
            PayoutStatusUpdatedEvent verifiedEvent = verified;
            requiresNewTransaction().executeWithoutResult(status ->
                    applyClaimedPayoutStatus(claimed, verifiedEvent)
            );
            return;
        }
        if (claimed.transferId() != null) {
            return;
        }
        if (claimed.recipientCode() == null) {
            requiresNewTransaction().executeWithoutResult(status -> stopPayoutAutomation(
                    claimed,
                    "Vendor payout recipient is unavailable"
            ));
            return;
        }
        Boolean claimValid = requiresNewTransaction().execute(status -> validatePayoutClaim(claimed));
        if (!Boolean.TRUE.equals(claimValid)) {
            return;
        }
        try {
            PayoutResponse response = payoutProvider.initiatePayout(new PayoutRequest(
                    claimed.recipientCode(),
                    claimed.amount(),
                    claimed.reference(),
                    "ShopSecure order " + claimed.orderId() + " seller settlement"
            ));
            if (!claimed.reference().equals(response.reference())) {
                requiresNewTransaction().executeWithoutResult(status ->
                        recordPayoutReferenceMismatch(claimed, response)
                );
                return;
            }
            requiresNewTransaction().executeWithoutResult(status -> applyRecoveredInitiation(
                    claimed,
                    response
            ));
        } catch (PayoutRejectedException exception) {
            requiresNewTransaction().executeWithoutResult(status -> stopPayoutAutomation(
                    claimed,
                    "Payment provider definitively rejected the payout; manual review is required"
            ));
        } catch (RuntimeException exception) {
            // The claim lease makes the deterministic reference eligible for later verification.
        }
    }

    private PayoutReconciliation claimPayoutReconciliation(Long settlementId, Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        OrderSettlement settlement = settlementRepository.findByIdForUpdate(settlementId).orElse(null);
        if (order == null
                || settlement == null
                || !order.getId().equals(settlement.getOrder().getId())
                || settlement.getSettlementType() != SettlementType.EXTERNAL_PAYOUT
                || (settlement.getStatus() != SettlementStatus.PENDING
                && settlement.getStatus() != SettlementStatus.DISPATCH_UNKNOWN)) {
            return null;
        }
        Instant now = Instant.now();
        if (settlement.getNextReconciliationAt() != null
                && settlement.getNextReconciliationAt().isAfter(now)) {
            return null;
        }
        String reconciliationToken = UUID.randomUUID().toString();
        settlement.setNextReconciliationAt(nextPayoutReconciliationAt(now));
        settlement.setReconciliationToken(reconciliationToken);
        settlementRepository.save(settlement);
        return new PayoutReconciliation(
                settlement.getId(),
                order.getId(),
                settlement.getAmount(),
                settlement.getPayoutReference(),
                settlement.getTransferId(),
                vendorRecipientCode(order),
                reconciliationToken
        );
    }

    private void applyRecoveredInitiation(PayoutReconciliation claimed, PayoutResponse response) {
        if (response.reference() == null || !claimed.reference().equals(response.reference())) {
            return;
        }
        Order order = orderRepository.findByIdForUpdate(claimed.orderId()).orElse(null);
        OrderSettlement settlement = settlementRepository.findByIdForUpdate(claimed.settlementId()).orElse(null);
        if (!payoutClaimMatches(settlement, claimed)) {
            return;
        }
        if (order == null) {
            return;
        }
        String ownershipMismatch = payoutTransferOwnershipMismatch(settlement, response.transferId());
        if (ownershipMismatch != null) {
            recordPayoutMismatchLocked(
                    order,
                    settlement,
                    response.reference(),
                    response.transferId(),
                    ownershipMismatch
            );
            return;
        }
        String mismatch = payoutResponseMismatch(order, settlement, response);
        if (mismatch != null) {
            recordPayoutMismatchLocked(
                    order,
                    settlement,
                    response.reference(),
                    response.transferId(),
                    mismatch
            );
            return;
        }
        setTransferId(settlement, response.transferId());
        PayoutStatus recoveredStatus = response.status() == PayoutStatus.SUCCESS
                && !hasCompletePayoutDetails(response)
                ? PayoutStatus.PENDING
                : response.status();
        applyPayoutState(order, settlement, recoveredStatus);
        settlement.setUpdatedAt(Instant.now());
        settlementRepository.save(settlement);
        orderRepository.save(order);
    }

    private void applyClaimedPayoutStatus(
            PayoutReconciliation claimed,
            PayoutStatusUpdatedEvent event
    ) {
        Order order = orderRepository.findByIdForUpdate(claimed.orderId()).orElse(null);
        OrderSettlement settlement = settlementRepository.findByIdForUpdate(claimed.settlementId()).orElse(null);
        if (order == null || !payoutClaimMatches(settlement, claimed) || !event.relevant()) {
            return;
        }
        if (!claimed.reference().equals(event.reference())) {
            recordPayoutMismatchLocked(
                    order,
                    settlement,
                    event.reference(),
                    event.transferId(),
                    "Provider verification returned a mismatched payout reference; manual reconciliation is required"
            );
            return;
        }
        applyPayoutStatusLocked(order, settlement, event);
    }

    private boolean validatePayoutClaim(PayoutReconciliation claimed) {
        if (orderRepository.findByIdForUpdate(claimed.orderId()).isEmpty()) {
            return false;
        }
        OrderSettlement settlement = settlementRepository.findByIdForUpdate(claimed.settlementId()).orElse(null);
        if (!payoutClaimMatches(settlement, claimed)) {
            return false;
        }
        settlement.setNextReconciliationAt(nextPayoutReconciliationAt(Instant.now()));
        settlementRepository.save(settlement);
        return true;
    }

    private void stopPayoutAutomation(PayoutReconciliation claimed, String reason) {
        Order order = orderRepository.findByIdForUpdate(claimed.orderId()).orElse(null);
        OrderSettlement settlement = settlementRepository.findByIdForUpdate(claimed.settlementId()).orElse(null);
        if (order == null || !payoutClaimMatches(settlement, claimed)) {
            return;
        }
        settlement.setStatus(SettlementStatus.REQUIRES_ACTION);
        settlement.setFailureReason(reason);
        settlement.setNextReconciliationAt(null);
        settlement.setReconciliationToken(null);
        settlement.setUpdatedAt(Instant.now());
        order.setSettlementStatus(SettlementStatus.REQUIRES_ACTION);
        settlementRepository.save(settlement);
        orderRepository.save(order);
    }

    private void recordPayoutReferenceMismatch(
            PayoutReconciliation claimed,
            PayoutResponse response
    ) {
        Order order = orderRepository.findByIdForUpdate(claimed.orderId()).orElse(null);
        OrderSettlement settlement = settlementRepository.findByIdForUpdate(claimed.settlementId()).orElse(null);
        if (order == null || !payoutClaimMatches(settlement, claimed)) {
            return;
        }
        recordPayoutMismatchLocked(
                order,
                settlement,
                response.reference(),
                response.transferId(),
                "Provider returned a mismatched payout reference; manual reconciliation is required"
        );
    }

    private String payoutResponseMismatch(
            Order order,
            OrderSettlement settlement,
            PayoutResponse response
    ) {
        if (settlement.getTransferId() != null
                && response.transferId() != null
                && !settlement.getTransferId().equals(response.transferId())) {
            return "Provider returned a conflicting payout transfer ID; manual reconciliation is required";
        }
        if (response.status() != PayoutStatus.SUCCESS) {
            return null;
        }
        if (response.amount() != null && settlement.getAmount().compareTo(response.amount()) != 0) {
            return "Provider payout amount does not match the frozen settlement; manual reconciliation is required";
        }
        if (response.currency() != null && !PAYOUT_CURRENCY.equalsIgnoreCase(response.currency())) {
            return "Provider payout currency does not match the frozen settlement; manual reconciliation is required";
        }
        String expectedRecipient = vendorRecipientCode(order);
        if (response.recipientId() != null && !response.recipientId().equals(expectedRecipient)) {
            return "Provider payout recipient does not match the frozen settlement; manual reconciliation is required";
        }
        return null;
    }

    private boolean hasCompletePayoutDetails(PayoutResponse response) {
        return response.amount() != null
                && response.currency() != null
                && !response.currency().isBlank()
                && response.recipientId() != null
                && !response.recipientId().isBlank();
    }

    private String payoutTransferOwnershipMismatch(
            OrderSettlement settlement,
            String transferId
    ) {
        if (transferId == null || transferId.isBlank()) {
            return null;
        }
        boolean ownedByAnotherSettlement = settlementRepository.findByTransferId(transferId)
                .map(owner -> !owner.getId().equals(settlement.getId()))
                .orElse(false);
        if (ownedByAnotherSettlement || withdrawalRepository.findByTransferId(transferId).isPresent()) {
            return "Provider transfer ID is already assigned to another payout; manual reconciliation is required";
        }
        return null;
    }

    private String payoutEventMismatch(
            Order order,
            OrderSettlement settlement,
            PayoutStatusUpdatedEvent event
    ) {
        if (settlement.getTransferId() != null
                && event.transferId() != null
                && !settlement.getTransferId().equals(event.transferId())) {
            return "Provider returned a conflicting payout transfer ID; manual reconciliation is required";
        }
        if (event.status() != PayoutStatus.SUCCESS || settlement.getStatus() == SettlementStatus.COMPLETED) {
            return null;
        }
        return payoutDetailsMismatch(
                order,
                settlement,
                event.amount(),
                event.currency(),
                event.recipientId()
        );
    }

    private String payoutDetailsMismatch(
            Order order,
            OrderSettlement settlement,
            BigDecimal amount,
            String currency,
            String recipientId
    ) {
        if (amount == null || settlement.getAmount().compareTo(amount) != 0) {
            return "Provider payout amount does not match the frozen settlement; manual reconciliation is required";
        }
        if (currency == null || !PAYOUT_CURRENCY.equalsIgnoreCase(currency)) {
            return "Provider payout currency does not match the frozen settlement; manual reconciliation is required";
        }
        String expectedRecipient = vendorRecipientCode(order);
        if (expectedRecipient == null || !expectedRecipient.equals(recipientId)) {
            return "Provider payout recipient does not match the frozen settlement; manual reconciliation is required";
        }
        return null;
    }

    private void recordPayoutMismatchLocked(
            Order order,
            OrderSettlement settlement,
            String unexpectedReference,
            String unexpectedTransferId,
            String reason
    ) {
        if (unexpectedReference != null && !settlement.getPayoutReference().equals(unexpectedReference)) {
            settlement.setUnexpectedPayoutReference(unexpectedReference);
        }
        if (unexpectedTransferId != null && !unexpectedTransferId.equals(settlement.getTransferId())) {
            settlement.setUnexpectedTransferId(unexpectedTransferId);
        }
        settlement.setStatus(SettlementStatus.REQUIRES_ACTION);
        settlement.setFailureReason(reason);
        settlement.setNextReconciliationAt(null);
        settlement.setReconciliationToken(null);
        settlement.setUpdatedAt(Instant.now());
        order.setSettlementStatus(SettlementStatus.REQUIRES_ACTION);
        settlementRepository.save(settlement);
        orderRepository.save(order);
    }

    private boolean payoutClaimMatches(
            OrderSettlement settlement,
            PayoutReconciliation claimed
    ) {
        return settlement != null
                && claimed.reference().equals(settlement.getPayoutReference())
                && claimed.reconciliationToken().equals(settlement.getReconciliationToken())
                && (settlement.getStatus() == SettlementStatus.PENDING
                || settlement.getStatus() == SettlementStatus.DISPATCH_UNKNOWN);
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

    private DeliveryConfirmationResponse dispatchPayout(
            PreparedPayout prepared,
            TransactionTemplate transaction
    ) {
        OrderSettlement settlement = settlementRepository.findByOrder_Id(prepared.orderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order settlement not found"));
        reconcilePayout(settlement);
        return transaction.execute(status -> orderRepository.findById(prepared.orderId())
                .map(this::response)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found")));
    }

    private DeliveryConfirmationResponse completeLegacyWalletSettlement(
            Order order,
            String idempotencyKey
    ) {
        walletService.creditOrderRelease(order, idempotencyKey);
        Instant now = Instant.now();
        settlementRepository.save(OrderSettlement.builder()
                .order(order)
                .settlementType(SettlementType.WALLET_CREDIT)
                .amount(order.getItemAmount())
                .status(SettlementStatus.COMPLETED)
                .idempotencyKey(idempotencyKey)
                .trigger(SettlementTrigger.BUYER_DELIVERY_CONFIRMATION)
                .triggeredAt(now)
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
        publishVendorSettlementCompleted(
                order,
                SettlementType.WALLET_CREDIT,
                SettlementTrigger.BUYER_DELIVERY_CONFIRMATION
        );
        return response(order);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCarrierPickup(Order order, OrderShipment shipment) {
        if (order == null
                || shipment == null
                || order.getId() == null
                || shipment.getOrder() == null
                || !order.getId().equals(shipment.getOrder().getId())) {
            throw new BusinessException("Carrier pickup does not match the order shipment");
        }
        if (shipment.getStatus() != ShipmentStatus.IN_TRANSIT
                && shipment.getStatus() != ShipmentStatus.DELIVERED) {
            return;
        }
        recordCarrierPickupEvidence(order, shipment, shipment.getProviderUpdatedAt());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCarrierPickupEvidence(
            Order order,
            OrderShipment shipment,
            Instant providerTime
    ) {
        if (order == null
                || shipment == null
                || order.getId() == null
                || shipment.getOrder() == null
                || !order.getId().equals(shipment.getOrder().getId())) {
            throw new BusinessException("Carrier pickup does not match the order shipment");
        }
        if (order.getCarrierPickupConfirmedAt() != null) {
            return;
        }
        if (order.getPaymentStatus() != PaymentStatus.CONFIRMED) {
            throw new BusinessException("Carrier pickup cannot release an unpaid order");
        }
        Instant now = Instant.now();
        Instant pickupConfirmedAt = providerTime == null || providerTime.isAfter(now) ? now : providerTime;
        order.setCarrierPickupConfirmedAt(pickupConfirmedAt);
        order.setUpdatedAt(now);
        if (order.getEscrowReleasePolicy() == EscrowReleasePolicy.CARRIER_PICKUP) {
            prepareCarrierPickupPayout(order, pickupConfirmedAt, now);
        }
        orderRepository.save(order);
        eventPublisher.publishEvent(new OrderCarrierPickupConfirmedEvent(order.getId()));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void dispatchCarrierPickupPayout(Long orderId) {
        OrderSettlement settlement = settlementRepository.findByOrder_Id(orderId).orElse(null);
        if (settlement == null
                || settlement.getTrigger() != SettlementTrigger.CARRIER_PICKUP
                || (settlement.getStatus() != SettlementStatus.PENDING
                && settlement.getStatus() != SettlementStatus.DISPATCH_UNKNOWN)) {
            return;
        }
        reconcilePayout(settlement);
    }

    private void prepareCarrierPickupPayout(
            Order order,
            Instant pickupConfirmedAt,
            Instant now
    ) {
        if (settlementRepository.findByOrderIdForUpdate(order.getId()).isPresent()) {
            return;
        }
        if (order.getStatus() != OrderStatus.READY_FOR_DELIVERY
                && order.getStatus() != OrderStatus.SHIPPING_FAILED
                && order.getStatus() != OrderStatus.MANUAL_REVIEW) {
            throw new BusinessException("Order is not ready for carrier-pickup settlement");
        }

        String recipientCode = vendorRecipientCode(order);
        boolean alreadyManual = order.getStatus() == OrderStatus.MANUAL_REVIEW;
        boolean automaticPayout = recipientCode != null && !alreadyManual;
        SettlementStatus initialStatus = automaticPayout
                ? SettlementStatus.PENDING
                : SettlementStatus.REQUIRES_ACTION;
        String failureReason = recipientCode == null
                ? "Vendor payout recipient is unavailable"
                : alreadyManual ? "Carrier pickup confirmed while the order requires manual review" : null;
        String reference = "shopsecure_order_" + order.getId() + "_payout";
        settlementRepository.save(OrderSettlement.builder()
                .order(order)
                .settlementType(SettlementType.EXTERNAL_PAYOUT)
                .amount(order.getItemAmount())
                .status(initialStatus)
                .idempotencyKey("carrier-pickup-order-" + order.getId())
                .trigger(SettlementTrigger.CARRIER_PICKUP)
                .triggeredAt(pickupConfirmedAt)
                .payoutReference(reference)
                .payoutAttempt(automaticPayout ? 1 : 0)
                .failureReason(failureReason)
                .nextReconciliationAt(automaticPayout ? now : null)
                .createdAt(now)
                .updatedAt(now)
                .build());
        order.setSettlementStatus(initialStatus);
        if (!alreadyManual) {
            statusService.transition(
                    order,
                    OrderStatus.PAYOUT_PENDING,
                    "Carrier confirmed pickup; vendor bank payout queued"
            );
        }
    }

    private String vendorRecipientCode(Order order) {
        if (order.getVendorPayoutRecipientCode() != null
                && !order.getVendorPayoutRecipientCode().isBlank()) {
            return order.getVendorPayoutRecipientCode();
        }
        if (order.getCreatorRole() != CreatorRole.BUYER) {
            return null;
        }
        return counterpartyRepository.findByOrder_Id(order.getId())
                .map(OrderCounterparty::getPayoutRecipientCode)
                .filter(code -> !code.isBlank())
                .orElse(null);
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
        settlement.setNextReconciliationAt(null);
        settlement.setReconciliationToken(null);
        order.setSettlementStatus(settlementStatus);
        if (order.getStatus() == OrderStatus.PAYOUT_PENDING
                || order.getStatus() == OrderStatus.IN_TRANSIT
                || order.getStatus() == OrderStatus.COMPLETED) {
            statusService.transition(order, OrderStatus.PAYOUT_FAILED, reason);
        }
    }

    private void publishVendorSettlementCompleted(
            Order order,
            SettlementType settlementType,
            SettlementTrigger trigger
    ) {
        eventPublisher.publishEvent(new VendorSettlementCompletedEvent(
                order.getId(),
                settlementType,
                trigger
        ));
    }

    private Instant nextPayoutReconciliationAt(Instant now) {
        Duration delay = payoutReconciliationRetryDelay;
        if (delay == null || delay.isZero() || delay.isNegative()) {
            delay = Duration.ofMinutes(1);
        }
        return now.plus(delay);
    }

    private void requireBuyerDeliveryConfirmationPolicy(Order order) {
        if (order.getEscrowReleasePolicy() == EscrowReleasePolicy.CARRIER_PICKUP) {
            throw new BusinessException("Vendor payout is automatic after carrier pickup");
        }
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

    private record PayoutReconciliation(
            Long settlementId,
            Long orderId,
            java.math.BigDecimal amount,
            String reference,
            String transferId,
            String recipientCode,
            String reconciliationToken
    ) {
    }

    private record GuestPreparation(
            PreparedPayout payout,
            OtpVerificationException verificationFailure
    ) {
    }
}
