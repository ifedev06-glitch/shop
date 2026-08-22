package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.WaybillQuoteResponse;
import com.example.shopsecure.dto.response.WaybillQuoteSelectionResponse;
import com.example.shopsecure.dto.response.WaybillShipmentResponse;
import com.example.shopsecure.entity.ProcessedWebhookEvent;
import com.example.shopsecure.entity.ShippingQuoteStatus;
import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillInvitation;
import com.example.shopsecure.entity.WaybillParcel;
import com.example.shopsecure.entity.WaybillParty;
import com.example.shopsecure.entity.WaybillPartyRole;
import com.example.shopsecure.entity.WaybillPayerType;
import com.example.shopsecure.entity.WaybillPaymentAttempt;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillShipment;
import com.example.shopsecure.entity.WaybillShippingQuote;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.event.WaybillCarrierCancellationConfirmedEvent;
import com.example.shopsecure.event.WaybillPickupArrangedEvent;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.provider.shipping.AddressValidationResult;
import com.example.shopsecure.provider.shipping.ParcelDetails;
import com.example.shopsecure.provider.shipping.ShipmentAddress;
import com.example.shopsecure.provider.shipping.ShipmentContact;
import com.example.shopsecure.provider.shipping.ShipmentRequest;
import com.example.shopsecure.provider.shipping.ShipmentResponse;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.provider.shipping.ShipmentStatusUpdatedEvent;
import com.example.shopsecure.provider.shipping.ShipmentTrackingStatus;
import com.example.shopsecure.provider.shipping.ShipmentUserAction;
import com.example.shopsecure.provider.shipping.ShippingProvider;
import com.example.shopsecure.provider.shipping.ShippingProviderCode;
import com.example.shopsecure.provider.shipping.ShippingRateOption;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import com.example.shopsecure.repository.ProcessedWebhookEventRepository;
import com.example.shopsecure.repository.WaybillParcelRepository;
import com.example.shopsecure.repository.WaybillPartyRepository;
import com.example.shopsecure.repository.WaybillPaymentAttemptRepository;
import com.example.shopsecure.repository.WaybillPaymentReceiptRepository;
import com.example.shopsecure.repository.WaybillRepository;
import com.example.shopsecure.repository.WaybillShipmentRepository;
import com.example.shopsecure.repository.WaybillShippingQuoteRepository;
import com.example.shopsecure.util.TrackingUrl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaybillShippingService {

    private static final String WEBHOOK_PROVIDER = "TERMINAL_AFRICA";
    private static final Set<ShipmentStatus> CANCELLABLE_STATUSES = Set.of(
            ShipmentStatus.DRAFT,
            ShipmentStatus.PENDING,
            ShipmentStatus.CONFIRMED
    );
    private static final Set<ShipmentStatus> ACTIVE_PROVIDER_STATUSES = Set.of(
            ShipmentStatus.PENDING,
            ShipmentStatus.CONFIRMED,
            ShipmentStatus.IN_TRANSIT
    );
    private static final Set<WaybillPaymentAttemptStatus> INVALIDATED_ATTEMPT_STATUSES = EnumSet.of(
            WaybillPaymentAttemptStatus.INITIALIZING,
            WaybillPaymentAttemptStatus.INITIALIZATION_UNKNOWN,
            WaybillPaymentAttemptStatus.INITIALIZED,
            WaybillPaymentAttemptStatus.FAILED,
            WaybillPaymentAttemptStatus.ABANDONED
    );

    private final ShippingProvider shippingProvider;
    private final ShippingProviderRoutingService shippingRoutingService;
    private final WaybillRepository waybillRepository;
    private final WaybillPartyRepository partyRepository;
    private final WaybillParcelRepository parcelRepository;
    private final WaybillPaymentAttemptRepository attemptRepository;
    private final WaybillPaymentReceiptRepository receiptRepository;
    private final WaybillShipmentRepository shipmentRepository;
    private final WaybillShippingQuoteRepository quoteRepository;
    private final ProcessedWebhookEventRepository webhookEventRepository;
    private final WaybillPricingService pricingService;
    private final WaybillStatusService statusService;
    private final OrderService orderService;
    private final WaybillAccessService accessService;
    private final WaybillNotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${shipping.quote-ttl:PT15M}")
    private Duration quoteTtl;

    @Value("${waybill.booking-reconciliation.max-attempts:5}")
    private int maxBookingAttempts;

    @Value("${waybill.booking-reconciliation.retry-delay:PT5M}")
    private Duration bookingRetryDelay;

    @Transactional(noRollbackFor = PaymentReviewRequiredException.class)
    public List<WaybillQuoteResponse> createQuotes(String publicId) {
        Waybill waybill = requireWaybillForUpdate(publicId);
        requireCreatorQuoteAccess(waybill);
        return createQuotes(waybill);
    }

    private List<WaybillQuoteResponse> createQuotes(Waybill waybill) {
        if (waybill.getStatus() == WaybillStatus.AWAITING_PAYMENT) {
            resetExpiredPaymentSelection(waybill, LocalDateTime.now(ZoneOffset.UTC));
        }
        if (waybill.getStatus() != WaybillStatus.AWAITING_QUOTE) {
            throw new BusinessException("Waybill is not awaiting a shipping quote");
        }

        WaybillParty sender = requireCompleteParty(waybill.getId(), WaybillPartyRole.SENDER);
        WaybillParty recipient = requireCompleteParty(waybill.getId(), WaybillPartyRole.RECIPIENT);
        WaybillParcel parcel = requireCompleteParcel(waybill.getId());
        WaybillShipment shipment = shipmentRepository.findByWaybillIdForUpdate(waybill.getId())
                .orElse(null);

        if (shipment != null) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            List<WaybillShippingQuote> available = quoteRepository
                    .findByShipment_IdAndStatusOrderByDeliveryFeeAsc(
                            shipment.getId(),
                            ShippingQuoteStatus.AVAILABLE
                    );
            available.stream()
                    .filter(quote -> !quote.getExpiresAt().isAfter(now))
                    .forEach(quote -> quote.setStatus(ShippingQuoteStatus.EXPIRED));
            quoteRepository.saveAll(available);
            List<WaybillShippingQuote> cached = available.stream()
                    .filter(quote -> quote.getExpiresAt().isAfter(now))
                    .toList();
            if (!cached.isEmpty()) {
                return cached.stream().map(this::quoteResponse).toList();
            }
        }

        ShipmentRequest request = shipmentRequest(waybill, sender, recipient, parcel);
        validateAddress(request.pickupAddress(), "sender");
        validateAddress(request.deliveryAddress(), "recipient");

        ShippingProvider.ShippingQuoteResult quoteResult;
        if (shipment == null) {
            ShippingProviderRoutingService.PreparedShipment prepared = shippingRoutingService.prepare(
                    request,
                    waybill.getCurrency()
            );
            ShipmentResponse response = requireShipmentResponse(prepared.preparation().shipment());
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            shipment = shipmentRepository.save(WaybillShipment.builder()
                    .waybill(waybill)
                    .provider(prepared.providerCode())
                    .providerShipmentId(response.shipmentId())
                    .status(response.status())
                    .trackingReference(response.trackingReference())
                    .trackingUrl(TrackingUrl.normalizeHttps(response.trackingUrl()))
                    .providerUpdatedAt(toLocalDateTime(response.updatedAt()))
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            quoteResult = prepared.preparation().quoteResult();
        } else {
            quoteResult = shippingRoutingService.quote(
                    shipment.getProvider(),
                    shipment.getProviderShipmentId(),
                    request,
                    waybill.getCurrency()
            );
        }

        List<ShippingRateOption> rates = quoteResult == null ? null : quoteResult.rates();
        if (rates == null || rates.isEmpty()) {
            throw new BusinessException("No shipping rates are currently available");
        }
        LocalDateTime expiresAt = quoteResult.expiresAt() == null
                ? LocalDateTime.now(ZoneOffset.UTC).plus(quoteTtl)
                : LocalDateTime.ofInstant(quoteResult.expiresAt(), ZoneOffset.UTC);
        WaybillShipment persistedShipment = shipment;
        return rates.stream()
                .map(rate -> persistQuote(waybill, persistedShipment, rate, expiresAt))
                .sorted(Comparator.comparing(WaybillQuoteResponse::deliveryFee))
                .toList();
    }

    private void resetExpiredPaymentSelection(Waybill waybill, LocalDateTime now) {
        WaybillShippingQuote selected = quoteRepository.findByWaybillIdAndStatusForUpdate(
                        waybill.getId(),
                        ShippingQuoteStatus.SELECTED
                )
                .orElse(null);
        boolean deadlineExpired = waybill.getPaymentDueAt() != null
                && !waybill.getPaymentDueAt().isAfter(now);
        boolean selectionExpired = selected != null && !selected.getExpiresAt().isAfter(now);
        if (!deadlineExpired && !selectionExpired) {
            return;
        }

        boolean capturedPayment = receiptRepository.existsByWaybill_IdAndProviderStatus(
                waybill.getId(),
                PaymentTransactionStatus.SUCCESS
        ) || attemptRepository.existsByWaybill_IdAndStatus(
                waybill.getId(),
                WaybillPaymentAttemptStatus.SUCCESS
        );
        boolean funded = hasText(waybill.getFundingPaymentReference())
                || capturedPayment
                || (waybill.getPaymentStatus() != WaybillPaymentStatus.PENDING
                && waybill.getPaymentStatus() != WaybillPaymentStatus.NOT_STARTED);
        if (funded) {
            waybill.setPaymentStatus(WaybillPaymentStatus.REVIEW);
            statusService.transition(
                    waybill,
                    WaybillStatus.PAYMENT_REVIEW,
                    "Expired quote has a captured or unreconciled waybill payment"
            );
            waybillRepository.save(waybill);
            throw new PaymentReviewRequiredException(
                    "Expired waybill quote requires payment review"
            );
        }

        if (selected != null) {
            selected.setStatus(ShippingQuoteStatus.EXPIRED);
            quoteRepository.save(selected);
        }
        List<WaybillPaymentAttempt> attempts = attemptRepository
                .findByWaybill_IdAndStatusInOrderByCreatedAtDesc(
                        waybill.getId(),
                        INVALIDATED_ATTEMPT_STATUSES
                );
        attempts.forEach(attempt -> {
            attempt.setStatus(WaybillPaymentAttemptStatus.SUPERSEDED);
            attempt.setFailureReason("Selected waybill shipping quote expired");
            attempt.setNextRecheckAt(now);
            attempt.setUpdatedAt(now);
            attemptRepository.save(attempt);
        });

        waybill.setProviderDeliveryFee(null);
        waybill.setDeliveryMarkup(null);
        waybill.setDeliveryFee(null);
        waybill.setServiceFee(null);
        waybill.setTotalAmount(null);
        waybill.setPaymentDueAt(null);
        waybill.setPaymentStatus(WaybillPaymentStatus.NOT_STARTED);
        statusService.transition(
                waybill,
                WaybillStatus.AWAITING_QUOTE,
                "Expired waybill shipping quote reset for fresh rates"
        );
        waybillRepository.save(waybill);
    }

    @Transactional(noRollbackFor = ExpiredQuoteException.class)
    public WaybillQuoteSelectionResponse selectQuote(String publicId, Long quoteId) {
        Waybill waybill = requireWaybillForUpdate(publicId);
        requireCreatorQuoteAccess(waybill);
        return selectQuote(waybill, quoteId, "Creator selected shipping quote");
    }

    private WaybillQuoteSelectionResponse selectQuote(Waybill waybill, Long quoteId, String reason) {
        WaybillShippingQuote selected = quoteRepository.findByIdAndWaybillIdForUpdate(
                        quoteId,
                        waybill.getId()
                )
                .orElseThrow(() -> new ResourceNotFoundException("Waybill shipping quote not found"));

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (waybill.getStatus() == WaybillStatus.AWAITING_PAYMENT
                && selected.getStatus() == ShippingQuoteStatus.SELECTED) {
            if (!selected.getExpiresAt().isAfter(now)
                    || waybill.getPaymentDueAt() == null
                    || !waybill.getPaymentDueAt().isAfter(now)) {
                throw new BusinessException("Shipping quote has expired; request fresh rates");
            }
            return new WaybillQuoteSelectionResponse(quoteResponse(selected), null, null);
        }
        if (waybill.getStatus() != WaybillStatus.AWAITING_QUOTE) {
            throw new BusinessException("Waybill is not awaiting quote selection");
        }
        if (selected.getStatus() != ShippingQuoteStatus.AVAILABLE) {
            throw new BusinessException("Shipping quote is no longer available");
        }
        if (!selected.getExpiresAt().isAfter(now)) {
            selected.setStatus(ShippingQuoteStatus.EXPIRED);
            quoteRepository.save(selected);
            throw new ExpiredQuoteException("Shipping quote has expired; request fresh rates");
        }

        quoteRepository.findByShipment_Id(selected.getShipment().getId()).forEach(quote -> {
            if (quote.getId().equals(selected.getId())) {
                quote.setStatus(ShippingQuoteStatus.SELECTED);
                quote.setSelectedAt(now);
            } else {
                quote.setStatus(ShippingQuoteStatus.EXPIRED);
            }
            quoteRepository.save(quote);
        });
        pricingService.freeze(waybill, selected);
        waybill.setPaymentDueAt(selected.getExpiresAt());
        waybill.setPaymentStatus(WaybillPaymentStatus.PENDING);
        transition(waybill, WaybillStatus.AWAITING_PAYMENT, reason);
        waybillRepository.save(waybill);
        WaybillAccessService.IssuedPaymentLink paymentLink = accessService.issuePaymentLink(waybill);
        return new WaybillQuoteSelectionResponse(
                quoteResponse(selected),
                paymentLink == null ? null : paymentLink.link(),
                paymentLink == null ? null : paymentLink.expiresAt()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void arrangePickup(Long waybillId) {
        Waybill waybill = requireWaybillForUpdate(waybillId);
        if (waybill.getPaymentStatus() != WaybillPaymentStatus.CONFIRMED) {
            throw new BusinessException("Waybill payment must be confirmed before booking shipping");
        }
        if (waybill.getStatus() != WaybillStatus.SHIPMENT_BOOKING
                && waybill.getStatus() != WaybillStatus.SHIPPING_FAILED) {
            throw new BusinessException("Waybill is not ready for shipment booking");
        }
        WaybillShipment shipment = shipmentRepository.findByWaybillIdForUpdate(waybillId)
                .orElseThrow(() -> new BusinessException("Waybill shipping draft was not found"));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (shipment.getNextBookingAttemptAt() != null
                && shipment.getNextBookingAttemptAt().isAfter(now)) {
            return;
        }
        ShippingProvider shipmentProvider = shippingRoutingService.require(shipment.getProvider());
        if (shipment.getBookingAttempt() >= maxBookingAttempts) {
            if (isChowdeck(shipment.getProvider())) {
                try {
                    ShipmentTrackingStatus tracking = requireTrackingStatus(
                            shipment.getProviderShipmentId(),
                            shipmentProvider.trackShipment(shipment.getProviderShipmentId())
                    );
                    if (!providerDraftCanBeBooked(waybill, shipment, tracking)) {
                        waybillRepository.save(waybill);
                        return;
                    }
                } catch (RuntimeException ignored) {
                    // Fall through to manual review when final reconciliation is unavailable.
                }
            }
            exhaustBookingAttempts(waybill, shipment);
            return;
        }
        if (waybill.getStatus() == WaybillStatus.SHIPPING_FAILED) {
            transition(waybill, WaybillStatus.SHIPMENT_BOOKING, "Retrying shipment booking");
        }

        shipment.setBookingAttempt(shipment.getBookingAttempt() + 1);
        shipment.setNextBookingAttemptAt(null);
        shipment.setFailureReason(null);
        shipment.setUpdatedAt(now);
        shipmentRepository.save(shipment);

        try {
            if (!isChowdeck(shipment.getProvider())) {
                ShipmentTrackingStatus tracking = requireTrackingStatus(
                        shipment.getProviderShipmentId(),
                        shipmentProvider.trackShipment(shipment.getProviderShipmentId())
                );
                if (!providerDraftCanBeBooked(waybill, shipment, tracking)) {
                    waybillRepository.save(waybill);
                    return;
                }
            }

            WaybillShippingQuote quote = selectedQuote(waybillId);
            BigDecimal providerWalletBalance = shipmentProvider.getWalletBalance();
            if (providerWalletBalance != null
                    && providerWalletBalance.compareTo(quote.getProviderAmount()) < 0) {
                throw new BusinessException("Shipping provider wallet requires funding before booking");
            }
            ShipmentResponse providerResponse;
            if (isChowdeck(shipment.getProvider())) {
                ShipmentRequest request = shipmentRequest(
                        waybill,
                        requireCompleteParty(waybillId, WaybillPartyRole.SENDER),
                        requireCompleteParty(waybillId, WaybillPartyRole.RECIPIENT),
                        requireCompleteParcel(waybillId)
                );
                providerResponse = shipmentProvider.arrangePickup(
                        shipment.getProviderShipmentId(),
                        quote.getProviderRateId(),
                        request
                );
            } else {
                providerResponse = shipmentProvider.arrangePickup(
                        shipment.getProviderShipmentId(),
                        quote.getProviderRateId()
                );
            }
            ShipmentResponse response = requireShipmentResponse(
                    providerResponse,
                    shipment.getProviderShipmentId()
            );
            if (!ACTIVE_PROVIDER_STATUSES.contains(response.status())
                    && response.status() != ShipmentStatus.DELIVERED) {
                throw new BusinessException("Shipping provider did not confirm the booking");
            }
            applyShipmentResponse(shipment, response);
            shipment.setNextBookingAttemptAt(null);
            shipment.setFailureReason(null);
            markQuoteUsed(quote);
            if (response.status() == ShipmentStatus.DELIVERED) {
                completeWaybill(waybill, response.updatedAt());
            } else {
                readyForDelivery(waybill, shipment, "Shipment pickup arranged");
            }
        } catch (RuntimeException exception) {
            recordBookingFailure(waybill, shipment);
            log.error("Waybill shipment booking failed: waybillId={}", waybillId);
        }
        waybillRepository.save(waybill);
    }

    @Transactional
    public WaybillShipmentResponse refreshTracking(String publicId) {
        Waybill waybill = requireWaybillForUpdate(publicId);
        requireCreator(waybill);
        WaybillShipment shipment = shipmentRepository.findByWaybillIdForUpdate(waybill.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Waybill shipment not found"));
        refreshTracking(waybill, shipment);
        return shipmentResponse(shipment);
    }

    @Transactional
    public void reconcileChowdeckTracking(Long waybillId) {
        Waybill waybill = requireWaybillForUpdate(waybillId);
        WaybillShipment shipment = shipmentRepository.findByWaybillIdForUpdate(waybillId).orElse(null);
        if (shipment == null
                || !isChowdeck(shipment.getProvider())
                || !ACTIVE_PROVIDER_STATUSES.contains(shipment.getStatus())) {
            return;
        }
        refreshTracking(waybill, shipment);
    }

    private void refreshTracking(Waybill waybill, WaybillShipment shipment) {
        ShippingProvider shipmentProvider = shippingRoutingService.require(shipment.getProvider());
        ShipmentTrackingStatus tracking = requireTrackingStatus(
                shipment.getProviderShipmentId(),
                shipmentProvider.trackShipment(shipment.getProviderShipmentId())
        );
        boolean cancellationConflict = shipment.getStatus() == ShipmentStatus.CANCELLED
                && tracking.currentStatus() == ShipmentStatus.DELIVERED;
        applyTrackingDetails(shipment, tracking);
        if (applyProviderStatus(
                shipment,
                tracking.currentStatus(),
                tracking.lastUpdated()
        )) {
            applyCarrierState(
                    waybill,
                    tracking.currentStatus(),
                    tracking.lastUpdated(),
                    shipment,
                    cancellationConflict
            );
        }
        shipment.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        shipmentRepository.save(shipment);
        waybillRepository.save(waybill);
    }

    @Transactional
    public ShipmentResponse cancelProviderShipment(Long waybillId) {
        Waybill waybill = requireWaybillForUpdate(waybillId);
        WaybillShipment shipment = shipmentRepository.findByWaybillIdForUpdate(waybillId)
                .orElseThrow(() -> new ResourceNotFoundException("Waybill shipment not found"));
        if (!CANCELLABLE_STATUSES.contains(shipment.getStatus())) {
            if (shipment.getStatus() == ShipmentStatus.IN_TRANSIT
                    || shipment.getStatus() == ShipmentStatus.DELIVERED) {
                throw new BusinessException("An in-transit or delivered shipment cannot be cancelled");
            }
            throw new BusinessException("Shipment is not in a cancellable state");
        }
        ShippingProvider shipmentProvider = shippingRoutingService.require(shipment.getProvider());
        ShipmentResponse response;
        if (shipment.getStatus() == ShipmentStatus.DRAFT && isChowdeck(shipment.getProvider())) {
            response = new ShipmentResponse(
                    shipment.getProviderShipmentId(),
                    ShipmentStatus.CANCELLED,
                    shipment.getTrackingReference(),
                    shipment.getTrackingUrl(),
                    Instant.now()
            );
        } else {
            ShipmentResponse cancellationResponse = isChowdeck(shipment.getProvider())
                    ? shipmentProvider.cancelShipment(
                            shipment.getProviderShipmentId(),
                            hasText(waybill.getCancellationReason())
                                    ? waybill.getCancellationReason()
                                    : "Waybill cancellation requested"
                    )
                    : shipmentProvider.cancelShipment(shipment.getProviderShipmentId());
            response = requireShipmentResponse(
                    cancellationResponse,
                    shipment.getProviderShipmentId()
            );
        }
        applyShipmentResponse(shipment, response);
        return response;
    }

    @Transactional(readOnly = true)
    public boolean ownsShipment(String providerShipmentId) {
        if (providerShipmentId == null) {
            return false;
        }
        return shipmentRepository.findByProviderShipmentId(providerShipmentId)
                .filter(shipment -> !isChowdeck(shipment.getProvider()))
                .isPresent();
    }

    @Transactional
    public void applyWebhookEvent(ShipmentStatusUpdatedEvent event, String eventKey) {
        if (event == null || !event.relevant() || event.shipmentId() == null) {
            return;
        }
        if (event.occurredAt() == null) {
            throw new BusinessException("Terminal Africa webhook timestamp is required");
        }
        String dedupeKey = boundedEventKey(eventKey);
        if (webhookEventRepository.existsByProviderAndEventKey(WEBHOOK_PROVIDER, dedupeKey)) {
            return;
        }
        WaybillShipment located = shipmentRepository.findByProviderShipmentId(event.shipmentId())
                .orElse(null);
        if (located == null || isChowdeck(located.getProvider())) {
            return;
        }

        Waybill waybill = requireWaybillForUpdate(located.getWaybill().getId());
        WaybillShipment shipment = shipmentRepository.findByWaybillIdForUpdate(waybill.getId())
                .orElse(null);
        if (shipment == null || !event.shipmentId().equals(shipment.getProviderShipmentId())) {
            return;
        }
        if (webhookEventRepository.existsByProviderAndEventKey(WEBHOOK_PROVIDER, dedupeKey)) {
            return;
        }
        boolean cancellationConflict = shipment.getStatus() == ShipmentStatus.CANCELLED
                && event.status() == ShipmentStatus.DELIVERED;
        boolean carrierCancellationConfirmed = false;
        if (applyProviderStatus(shipment, event.status(), event.occurredAt())) {
            applyCarrierState(
                    waybill,
                    event.status(),
                    event.occurredAt(),
                    shipment,
                    cancellationConflict
            );
            shipment.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            shipmentRepository.save(shipment);
            waybillRepository.save(waybill);
            carrierCancellationConfirmed = event.status() == ShipmentStatus.CANCELLED
                    && waybill.getStatus() == WaybillStatus.CANCELLATION_PENDING;
        }
        webhookEventRepository.save(ProcessedWebhookEvent.builder()
                .provider(WEBHOOK_PROVIDER)
                .eventKey(dedupeKey)
                .createdAt(Instant.now())
                .build());
        if (carrierCancellationConfirmed) {
            eventPublisher.publishEvent(new WaybillCarrierCancellationConfirmedEvent(waybill.getId()));
        }
    }

    private boolean providerDraftCanBeBooked(
            Waybill waybill,
            WaybillShipment shipment,
            ShipmentTrackingStatus tracking
    ) {
        if (tracking == null || tracking.currentStatus() == null) {
            throw new BusinessException("Shipping provider returned no tracking state");
        }
        boolean accepted = applyProviderStatus(
                shipment,
                tracking.currentStatus(),
                tracking.lastUpdated()
        );
        if (accepted || !hasText(shipment.getTrackingUrl())) {
            applyTrackingDetails(shipment, tracking);
        }
        ShipmentStatus effectiveStatus = shipment.getStatus();
        if (effectiveStatus == ShipmentStatus.DRAFT) {
            if (tracking.currentStatus() != ShipmentStatus.DRAFT) {
                throw new BusinessException("Shipping provider returned a stale shipment state");
            }
            shipment.setNextBookingAttemptAt(null);
            shipment.setFailureReason(null);
            shipment.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            shipmentRepository.save(shipment);
            return true;
        }
        if (ACTIVE_PROVIDER_STATUSES.contains(effectiveStatus)) {
            shipment.setNextBookingAttemptAt(null);
            shipment.setFailureReason(null);
            shipment.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            shipmentRepository.save(shipment);
            markQuoteUsed(selectedQuote(waybill.getId()));
            readyForDelivery(waybill, shipment, "Shipment recovered and active");
            return false;
        }
        if (effectiveStatus == ShipmentStatus.DELIVERED) {
            shipment.setNextBookingAttemptAt(null);
            shipment.setFailureReason(null);
            shipment.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            shipmentRepository.save(shipment);
            markQuoteUsed(selectedQuote(waybill.getId()));
            completeWaybill(waybill, tracking.lastUpdated());
            return false;
        }
        if (effectiveStatus == ShipmentStatus.CANCELLED
                || effectiveStatus == ShipmentStatus.FAILED) {
            shipment.setNextBookingAttemptAt(null);
            shipment.setFailureReason("Carrier reports a terminal shipment state");
            shipment.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            shipmentRepository.save(shipment);
            transition(waybill, WaybillStatus.MANUAL_REVIEW, shipment.getFailureReason());
            return false;
        }
        throw new BusinessException("Shipping provider state is not safe to book");
    }

    private void recordBookingFailure(Waybill waybill, WaybillShipment shipment) {
        shipment.setFailureReason("Shipment booking attempt failed");
        if (shipment.getBookingAttempt() >= maxBookingAttempts) {
            if (isChowdeck(shipment.getProvider())) {
                shipment.setNextBookingAttemptAt(
                        LocalDateTime.now(ZoneOffset.UTC).plus(retryDelay(shipment.getBookingAttempt()))
                );
                transition(
                        waybill,
                        WaybillStatus.SHIPPING_FAILED,
                        "Final carrier state verification required"
                );
            } else {
                shipment.setNextBookingAttemptAt(null);
                transition(
                        waybill,
                        WaybillStatus.MANUAL_REVIEW,
                        "Automated shipment booking retry limit reached"
                );
            }
        } else {
            shipment.setNextBookingAttemptAt(
                    LocalDateTime.now(ZoneOffset.UTC).plus(retryDelay(shipment.getBookingAttempt()))
            );
            transition(waybill, WaybillStatus.SHIPPING_FAILED, "Shipment booking failed");
        }
        shipment.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        shipmentRepository.save(shipment);
    }

    private void exhaustBookingAttempts(Waybill waybill, WaybillShipment shipment) {
        shipment.setFailureReason("Automated shipment booking retry limit reached");
        shipment.setNextBookingAttemptAt(null);
        shipment.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        shipmentRepository.save(shipment);
        transition(waybill, WaybillStatus.MANUAL_REVIEW, shipment.getFailureReason());
        waybillRepository.save(waybill);
    }

    private Duration retryDelay(int attempt) {
        return bookingRetryDelay.multipliedBy(Math.max(1, attempt));
    }

    private void applyCarrierState(
            Waybill waybill,
            ShipmentStatus status,
            Instant occurredAt,
            WaybillShipment shipment,
            boolean cancellationConflict
    ) {
        if (status == ShipmentStatus.DELIVERED) {
            completeWaybill(waybill, occurredAt, cancellationConflict);
            return;
        }
        if ((status == ShipmentStatus.CANCELLED || status == ShipmentStatus.FAILED)
                && waybill.getStatus() != WaybillStatus.CANCELLATION_PENDING
                && (waybill.getStatus() == WaybillStatus.SHIPMENT_BOOKING
                || waybill.getStatus() == WaybillStatus.READY_FOR_DELIVERY)) {
            transition(
                    waybill,
                    WaybillStatus.SHIPPING_FAILED,
                    "Carrier shipment failed or cancelled"
            );
            return;
        }
        if (ACTIVE_PROVIDER_STATUSES.contains(status)
                && waybill.getStatus() == WaybillStatus.SHIPPING_FAILED) {
            transition(waybill, WaybillStatus.SHIPMENT_BOOKING, "Carrier confirmed shipment recovery");
            readyForDelivery(waybill, shipment, "Shipment recovered and active");
        }
    }

    private boolean applyProviderStatus(
            WaybillShipment shipment,
            ShipmentStatus target,
            Instant providerUpdatedAt
    ) {
        LocalDateTime providerTime = toLocalDateTime(providerUpdatedAt);
        if (providerTime != null && shipment.getProviderUpdatedAt() != null
                && !providerTime.isAfter(shipment.getProviderUpdatedAt())) {
            return false;
        }
        if (shipment.getStatus() == ShipmentStatus.CANCELLED
                && target == ShipmentStatus.DELIVERED
                && (providerTime == null
                || (shipment.getProviderUpdatedAt() != null
                && !providerTime.isAfter(shipment.getProviderUpdatedAt())))) {
            return false;
        }
        if (!canAdvance(shipment.getStatus(), target)) {
            return false;
        }
        shipment.setStatus(target);
        if (providerTime != null) {
            shipment.setProviderUpdatedAt(providerTime);
        }
        return true;
    }

    private boolean canAdvance(ShipmentStatus current, ShipmentStatus target) {
        if (target == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        if (current == ShipmentStatus.FAILED) {
            return target == ShipmentStatus.PENDING
                    || target == ShipmentStatus.CONFIRMED
                    || target == ShipmentStatus.IN_TRANSIT
                    || target == ShipmentStatus.DELIVERED;
        }
        if (current == ShipmentStatus.CANCELLED) {
            return target == ShipmentStatus.CANCELLED || target == ShipmentStatus.DELIVERED;
        }
        if (current == ShipmentStatus.DELIVERED) {
            return target == ShipmentStatus.DELIVERED;
        }
        if (target == ShipmentStatus.CANCELLED || target == ShipmentStatus.FAILED) {
            return true;
        }
        return statusRank(target) >= statusRank(current);
    }

    private int statusRank(ShipmentStatus status) {
        return switch (status) {
            case UNKNOWN -> 0;
            case DRAFT -> 1;
            case PENDING -> 2;
            case CONFIRMED -> 3;
            case IN_TRANSIT -> 4;
            case DELIVERED -> 5;
            case CANCELLED, FAILED -> 6;
        };
    }

    private WaybillQuoteResponse persistQuote(
            Waybill waybill,
            WaybillShipment shipment,
            ShippingRateOption rate,
            LocalDateTime expiresAt
    ) {
        if (rate == null || rate.currency() == null
                || !waybill.getCurrency().equalsIgnoreCase(rate.currency())) {
            throw new BusinessException("Shipping rate currency does not match the waybill");
        }
        WaybillPricingService.Pricing pricing = pricingService.price(
                waybill,
                rate.providerAmount()
        );
        WaybillShippingQuote quote = quoteRepository
                .findByShipment_IdAndProviderRateId(shipment.getId(), rate.providerRateId())
                .orElseGet(() -> WaybillShippingQuote.builder()
                        .shipment(shipment)
                        .providerRateId(rate.providerRateId())
                        .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                        .build());
        quote.setProviderInternalId(rate.providerInternalId());
        quote.setCarrierName(rate.carrierName());
        quote.setCarrierLogo(rate.carrierLogo());
        quote.setServiceDescription(rate.serviceDescription());
        quote.setProviderAmount(pricing.providerFee());
        quote.setDeliveryMarkup(pricing.deliveryMarkup());
        quote.setDeliveryFee(pricing.deliveryFee());
        quote.setServiceFee(pricing.serviceFee());
        quote.setTotalAmount(pricing.total());
        quote.setCurrency(pricing.currency());
        quote.setPickupEstimate(rate.pickupEstimate());
        quote.setDeliveryEstimate(rate.deliveryEstimate());
        quote.setStatus(ShippingQuoteStatus.AVAILABLE);
        quote.setSelectedAt(null);
        quote.setExpiresAt(expiresAt);
        return quoteResponse(quoteRepository.save(quote));
    }

    private ShipmentRequest shipmentRequest(
            Waybill waybill,
            WaybillParty sender,
            WaybillParty recipient,
            WaybillParcel parcel
    ) {
        return new ShipmentRequest(
                address(sender),
                address(recipient),
                new ParcelDetails(
                        parcel.getWeightKg(),
                        parcel.getLengthCm(),
                        parcel.getWidthCm(),
                        parcel.getHeightCm(),
                        waybill.getDescription(),
                        waybill.getDescription(),
                        waybill.getDeclaredValue(),
                        waybill.getCurrency(),
                        parcel.getQuantity(),
                        parcel.getParcelType()
                ),
                contact(sender),
                contact(recipient),
                "waybill-" + waybill.getPublicId(),
                waybill.getCreatorRole() == WaybillPartyRole.SENDER
                        ? ShipmentUserAction.SENDING
                        : ShipmentUserAction.RECEIVING
        );
    }

    private ShipmentAddress address(WaybillParty party) {
        return new ShipmentAddress(
                party.getAddressLine1(),
                party.getAddressLine2(),
                party.getCity(),
                party.getState(),
                party.getCountry(),
                party.getPostalCode()
        );
    }

    private ShipmentContact contact(WaybillParty party) {
        return new ShipmentContact(
                party.getFirstName(),
                party.getLastName(),
                party.getEmail(),
                party.getPhoneNumber()
        );
    }

    private void validateAddress(ShipmentAddress address, String label) {
        if (address == null || !"NG".equalsIgnoreCase(address.country())) {
            throw new BusinessException("Only Nigerian shipping addresses are supported");
        }
        AddressValidationResult result = shippingProvider.validateAddress(address);
        if (result == null || !result.valid()) {
            throw new BusinessException("The " + label + " address could not be validated");
        }
    }

    private WaybillParty requireCompleteParty(Long waybillId, WaybillPartyRole role) {
        WaybillParty party = partyRepository.findByWaybill_IdAndRole(waybillId, role)
                .orElseThrow(() -> new BusinessException(role + " details are required"));
        if (party.getDetailsCompletedAt() == null
                || !hasText(party.getFirstName())
                || !hasText(party.getLastName())
                || !hasText(party.getEmail())
                || !hasText(party.getPhoneNumber())
                || !hasText(party.getAddressLine1())
                || !hasText(party.getCity())
                || !hasText(party.getState())
                || !hasText(party.getCountry())
                || !hasText(party.getPostalCode())) {
            throw new BusinessException(role + " details are incomplete");
        }
        return party;
    }

    private WaybillParcel requireCompleteParcel(Long waybillId) {
        WaybillParcel parcel = parcelRepository.findByWaybill_Id(waybillId)
                .orElseThrow(() -> new BusinessException("Waybill parcel details are required"));
        if (parcel.getWeightKg() == null || parcel.getWeightKg().signum() <= 0
                || parcel.getQuantity() <= 0 || !hasText(parcel.getParcelType())) {
            throw new BusinessException("Waybill parcel details are incomplete");
        }
        return parcel;
    }

    private void requireCreator(Waybill waybill) {
        if (!waybill.getCreator().getId().equals(orderService.currentUser().getId())) {
            throw new AccessDeniedException("Waybill does not belong to current user");
        }
    }

    private void requireCreatorQuoteAccess(Waybill waybill) {
        requireCreator(waybill);
    }

    private Waybill requireWaybillForUpdate(String publicId) {
        return waybillRepository.findByPublicIdForUpdate(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Waybill not found"));
    }

    private Waybill requireWaybillForUpdate(Long waybillId) {
        return waybillRepository.findByIdForUpdate(waybillId)
                .orElseThrow(() -> new ResourceNotFoundException("Waybill not found"));
    }

    private WaybillShippingQuote selectedQuote(Long waybillId) {
        return quoteRepository.findByWaybillIdAndStatusForUpdate(
                        waybillId,
                        ShippingQuoteStatus.SELECTED
                )
                .orElseGet(() -> quoteRepository.findByWaybillIdAndStatusForUpdate(
                                waybillId,
                                ShippingQuoteStatus.USED
                        )
                        .orElseThrow(() -> new BusinessException(
                                "Selected waybill shipping quote was not found"
                        )));
    }

    private void markQuoteUsed(WaybillShippingQuote quote) {
        if (quote.getStatus() == ShippingQuoteStatus.SELECTED) {
            quote.setStatus(ShippingQuoteStatus.USED);
            quoteRepository.save(quote);
        }
    }

    private void applyShipmentResponse(WaybillShipment shipment, ShipmentResponse response) {
        shipment.setStatus(response.status());
        if (hasText(response.trackingReference())) {
            shipment.setTrackingReference(response.trackingReference());
        }
        String trackingUrl = TrackingUrl.normalizeHttps(response.trackingUrl());
        if (trackingUrl != null) {
            shipment.setTrackingUrl(trackingUrl);
        }
        shipment.setProviderUpdatedAt(toLocalDateTime(response.updatedAt()));
        shipment.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        shipmentRepository.save(shipment);
    }

    private ShipmentResponse requireShipmentResponse(ShipmentResponse response) {
        if (response == null || !hasText(response.shipmentId()) || response.status() == null) {
            throw new BusinessException("Shipping provider returned an invalid shipment response");
        }
        return response;
    }

    private ShipmentResponse requireShipmentResponse(
            ShipmentResponse response,
            String expectedShipmentId
    ) {
        ShipmentResponse required = requireShipmentResponse(response);
        if (!required.shipmentId().equals(expectedShipmentId)) {
            throw new BusinessException("Shipping provider returned a mismatched shipment response");
        }
        return required;
    }

    private ShipmentTrackingStatus requireTrackingStatus(
            String expectedShipmentId,
            ShipmentTrackingStatus tracking
    ) {
        if (tracking == null
                || tracking.currentStatus() == null
                || !expectedShipmentId.equals(tracking.shipmentId())) {
            throw new BusinessException("Shipping provider returned a mismatched tracking response");
        }
        return tracking;
    }

    private void completeWaybill(Waybill waybill, Instant occurredAt) {
        completeWaybill(waybill, occurredAt, false);
    }

    private void completeWaybill(
            Waybill waybill,
            Instant occurredAt,
            boolean cancellationConflict
    ) {
        if (cancellationConflict) {
            if (waybill.getStatus() == WaybillStatus.AWAITING_PAYMENT) {
                transition(
                        waybill,
                        WaybillStatus.PAYMENT_REVIEW,
                        "Carrier delivery conflicts with prior cancellation"
                );
            }
            if (waybill.getStatus() != WaybillStatus.MANUAL_REVIEW) {
                transition(
                        waybill,
                        WaybillStatus.MANUAL_REVIEW,
                        "Carrier delivered after reporting shipment cancellation"
                );
            }
            return;
        }
        if (waybill.getStatus() == WaybillStatus.COMPLETED) {
            return;
        }
        if (waybill.getStatus() == WaybillStatus.CANCELLATION_PENDING) {
            transition(waybill, WaybillStatus.MANUAL_REVIEW, "Carrier delivered during cancellation");
            return;
        }
        if (waybill.getStatus() == WaybillStatus.CANCELLED
                || waybill.getStatus() == WaybillStatus.REFUND_PENDING
                || waybill.getStatus() == WaybillStatus.REFUNDED
                || (waybill.getStatus() == WaybillStatus.MANUAL_REVIEW
                && waybill.getCancellationRequestedAt() != null)) {
            if (waybill.getStatus() != WaybillStatus.MANUAL_REVIEW) {
                transition(
                        waybill,
                        WaybillStatus.MANUAL_REVIEW,
                        "Carrier delivered after waybill cancellation"
                );
            }
            return;
        }
        if (waybill.getStatus() == WaybillStatus.SHIPPING_FAILED) {
            transition(waybill, WaybillStatus.SHIPMENT_BOOKING, "Carrier confirmed shipment recovery");
        }
        if (waybill.getStatus() == WaybillStatus.SHIPMENT_BOOKING) {
            transition(waybill, WaybillStatus.READY_FOR_DELIVERY, "Carrier confirmed active shipment");
        }
        LocalDateTime completedAt = toLocalDateTime(occurredAt);
        waybill.setCompletedAt(completedAt == null
                ? LocalDateTime.now(ZoneOffset.UTC)
                : completedAt);
        transition(waybill, WaybillStatus.COMPLETED, "Carrier confirmed delivery");
    }

    private void transition(Waybill waybill, WaybillStatus target, String reason) {
        statusService.transition(waybill, target, reason);
    }

    private void readyForDelivery(
            Waybill waybill,
            WaybillShipment shipment,
            String reason
    ) {
        if (statusService.transition(waybill, WaybillStatus.READY_FOR_DELIVERY, reason)) {
            notificationService.enqueueAwaitingPickup(waybill, shipment);
            eventPublisher.publishEvent(new WaybillPickupArrangedEvent(waybill.getId()));
        }
    }

    private void applyTrackingDetails(
            WaybillShipment shipment,
            ShipmentTrackingStatus tracking
    ) {
        if (hasText(tracking.trackingReference())) {
            shipment.setTrackingReference(tracking.trackingReference());
        }
        String trackingUrl = TrackingUrl.normalizeHttps(tracking.trackingUrl());
        if (trackingUrl != null) {
            shipment.setTrackingUrl(trackingUrl);
        }
    }

    private WaybillQuoteResponse quoteResponse(WaybillShippingQuote quote) {
        return new WaybillQuoteResponse(
                quote.getId(),
                quote.getCarrierName(),
                quote.getCarrierLogo(),
                quote.getServiceDescription(),
                quote.getDeliveryFee(),
                quote.getServiceFee(),
                quote.getTotalAmount(),
                quote.getCurrency(),
                quote.getPickupEstimate(),
                quote.getDeliveryEstimate(),
                quote.getStatus(),
                quote.getExpiresAt()
        );
    }

    private WaybillShipmentResponse shipmentResponse(WaybillShipment shipment) {
        return new WaybillShipmentResponse(
                shipment.getProvider(),
                shipment.getProviderShipmentId(),
                shipment.getStatus(),
                shipment.getTrackingReference(),
                shipment.getTrackingUrl(),
                shipment.getFailureReason(),
                shipment.getProviderUpdatedAt(),
                shipment.getUpdatedAt()
        );
    }

    private String boundedEventKey(String eventKey) {
        if (!hasText(eventKey)) {
            throw new IllegalArgumentException("Webhook event key is required");
        }
        if (eventKey.length() <= 160) {
            return eventKey;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(eventKey.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to fingerprint webhook event key", exception);
        }
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isChowdeck(String provider) {
        return provider != null && ShippingProviderCode.CHOWDECK.equalsIgnoreCase(provider);
    }

    private static final class PaymentReviewRequiredException extends BusinessException {
        private PaymentReviewRequiredException(String message) {
            super(message);
        }
    }

    private static final class ExpiredQuoteException extends BusinessException {
        private ExpiredQuoteException(String message) {
            super(message);
        }
    }
}
