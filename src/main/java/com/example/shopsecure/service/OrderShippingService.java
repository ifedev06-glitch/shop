package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.*;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.provider.common.WebhookPayload;
import com.example.shopsecure.provider.shipping.*;
import com.example.shopsecure.repository.*;
import com.example.shopsecure.util.TrackingUrl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderShippingService {

    private static final Set<ShipmentStatus> ACTIVE_TRACKING_STATUSES = Set.of(
            ShipmentStatus.PENDING,
            ShipmentStatus.CONFIRMED,
            ShipmentStatus.IN_TRANSIT
    );

    private final ShippingProvider shippingProvider;
    private final ShippingProviderRoutingService shippingRoutingService;
    private final OrderRepository orderRepository;
    private final OrderCounterpartyRepository counterpartyRepository;
    private final OrderParcelRepository parcelRepository;
    private final OrderShipmentRepository shipmentRepository;
    private final ShippingQuoteRepository quoteRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final OrderPaymentAttemptRepository paymentAttemptRepository;
    private final ProcessedWebhookEventRepository webhookEventRepository;
    private final OrderPricingService pricingService;
    private final OrderStatusService statusService;
    private final OrderService orderService;
    private final OrderTrackingService trackingService;
    private final OrderSettlementService settlementService;

    @Value("${shipping.quote-ttl:PT15M}")
    private Duration quoteTtl;

    @Value("${shipping.booking-reconciliation.max-attempts:5}")
    private int maxBookingAttempts;

    @Value("${shipping.booking-reconciliation.retry-delay:PT5M}")
    private Duration bookingRetryDelay;

    @Transactional(noRollbackFor = PaymentReviewRequiredException.class)
    public List<ShippingQuoteResponse> createCreatorQuotes(Long orderId) {
        Order order = requireOrderForUpdate(orderId);
        orderService.requireOwner(order);
        resetExpiredPaymentSelection(order, Instant.now());
        return createQuotes(order);
    }

    @Transactional
    public ShippingSelectionResponse selectCreatorQuote(Long orderId, Long quoteId) {
        Order order = requireOrderForUpdate(orderId);
        orderService.requireOwner(order);
        return selectQuote(order, quoteId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void arrangePickup(Long orderId) {
        Order order = requireOrderForUpdate(orderId);
        if (order.getPaymentStatus() != PaymentStatus.CONFIRMED) {
            throw new BusinessException("Order payment must be confirmed before booking shipping");
        }
        if (order.getStatus() == OrderStatus.READY_FOR_DELIVERY
                || order.getStatus() == OrderStatus.PAYOUT_PENDING
                || order.getStatus() == OrderStatus.IN_TRANSIT
                || order.getStatus() == OrderStatus.COMPLETED) {
            return;
        }
        OrderShipment shipment = shipmentRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException("Shipping draft was not found"));
        if (order.getStatus() != OrderStatus.SHIPPING_FAILED
                && order.getStatus() != OrderStatus.SHIPMENT_BOOKING) {
            throw new BusinessException("Order is not ready for shipment booking");
        }
        if (shipment.getNextBookingAttemptAt() != null
                && shipment.getNextBookingAttemptAt().isAfter(Instant.now())) {
            return;
        }
        ShippingProvider shipmentProvider = shippingRoutingService.require(shipment.getProvider());
        if (!isChowdeck(shipment.getProvider())
                && !providerDraftCanBeBooked(order, shipment, shipmentProvider)) {
            return;
        }
        if (shipment.getBookingAttempt() >= maxBookingAttempts) {
            if (isChowdeck(shipment.getProvider())) {
                try {
                    ShipmentTrackingStatus tracking = requireTrackingStatus(
                            shipment.getProviderShipmentId(),
                            shipmentProvider.trackShipment(shipment.getProviderShipmentId())
                    );
                    if (!applyProviderDraftState(order, shipment, tracking)) {
                        return;
                    }
                } catch (RuntimeException ignored) {
                    // Fall through to manual review when final reconciliation is unavailable.
                }
            }
            shipment.setFailureReason("Automated shipment booking retry limit reached");
            shipment.setNextBookingAttemptAt(null);
            shipment.setUpdatedAt(Instant.now());
            shipmentRepository.save(shipment);
            statusService.transition(order, OrderStatus.MANUAL_REVIEW, shipment.getFailureReason());
            orderRepository.save(order);
            return;
        }
        if (order.getStatus() == OrderStatus.SHIPPING_FAILED) {
            statusService.transition(order, OrderStatus.SHIPMENT_BOOKING, "Retrying shipment booking");
        }
        ShippingQuote quote = selectedQuote(orderId);
        shipment.setBookingAttempt(shipment.getBookingAttempt() + 1);
        shipment.setNextBookingAttemptAt(null);
        shipment.setFailureReason(null);
        shipment.setUpdatedAt(Instant.now());
        shipmentRepository.save(shipment);
        boolean carrierPickupConfirmed = false;
        try {
            BigDecimal providerWalletBalance = shipmentProvider.getWalletBalance();
            if (providerWalletBalance != null
                    && providerWalletBalance.compareTo(quote.getProviderAmount()) < 0) {
                throw new BusinessException("Shipping provider wallet requires funding before booking");
            }
            ShipmentResponse providerResponse;
            if (isChowdeck(shipment.getProvider())) {
                ShipmentRequest request = shipmentRequest(
                        order,
                        counterpartyRepository.findByOrder_Id(orderId)
                                .orElseThrow(() -> new BusinessException("Counterparty details are required")),
                        parcelRepository.findByOrder_Id(orderId)
                                .orElseThrow(() -> new BusinessException("Vendor parcel details are required"))
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
            if (response.status() != ShipmentStatus.CONFIRMED
                    && response.status() != ShipmentStatus.PENDING
                    && response.status() != ShipmentStatus.IN_TRANSIT
                    && response.status() != ShipmentStatus.DELIVERED) {
                throw new BusinessException("Shipping provider did not confirm the booking");
            }
            applyShipmentResponse(shipment, response);
            shipment.setNextBookingAttemptAt(null);
            shipment.setFailureReason(null);
            quote.setStatus(ShippingQuoteStatus.USED);
            quoteRepository.save(quote);
            statusService.transition(order, OrderStatus.READY_FOR_DELIVERY, "Shipment pickup arranged");
            carrierPickupConfirmed = pickupConfirmed(response.status());
        } catch (RuntimeException exception) {
            shipment.setFailureReason("Shipment booking attempt failed");
            if (shipment.getBookingAttempt() >= maxBookingAttempts) {
                shipment.setNextBookingAttemptAt(Instant.now().plus(retryDelay(shipment.getBookingAttempt())));
                statusService.transition(
                        order,
                        OrderStatus.SHIPPING_FAILED,
                        "Final carrier state verification required"
                );
            } else {
                shipment.setNextBookingAttemptAt(Instant.now().plus(retryDelay(shipment.getBookingAttempt())));
                statusService.transition(order, OrderStatus.SHIPPING_FAILED, "Shipment booking failed");
            }
            shipment.setUpdatedAt(Instant.now());
            shipmentRepository.save(shipment);
            log.error("Shipment booking failed: orderId={}", orderId);
        }
        if (carrierPickupConfirmed) {
            settlementService.recordCarrierPickup(order, shipment);
        }
        orderRepository.save(order);
    }

    private boolean providerDraftCanBeBooked(
            Order order,
            OrderShipment shipment,
            ShippingProvider shipmentProvider
    ) {
        ShipmentTrackingStatus tracking;
        try {
            tracking = requireTrackingStatus(
                    shipment.getProviderShipmentId(),
                    shipmentProvider.trackShipment(shipment.getProviderShipmentId())
            );
        } catch (RuntimeException exception) {
            deferBookingPreflight(shipment, "Unable to verify shipment state before booking");
            log.warn("Unable to verify shipment before booking: orderId={}", order.getId());
            return false;
        }
        return applyProviderDraftState(order, shipment, tracking);
    }

    private boolean applyProviderDraftState(
            Order order,
            OrderShipment shipment,
            ShipmentTrackingStatus tracking
    ) {
        if (tracking == null || tracking.currentStatus() == null) {
            deferBookingPreflight(shipment, "Shipping provider returned no tracking state");
            return false;
        }
        boolean accepted = applyProviderStatus(
                shipment,
                tracking.currentStatus(),
                tracking.lastUpdated()
        );
        if (accepted || !hasText(shipment.getTrackingUrl())) {
            applyTrackingDetails(shipment, tracking, accepted);
        }
        ShipmentStatus effectiveStatus = shipment.getStatus();
        if (effectiveStatus == ShipmentStatus.DRAFT) {
            if (tracking.currentStatus() != ShipmentStatus.DRAFT) {
                deferBookingPreflight(shipment, "Shipping provider returned a stale shipment state");
                return false;
            }
            shipment.setNextBookingAttemptAt(null);
            shipment.setFailureReason(null);
            shipment.setUpdatedAt(Instant.now());
            shipmentRepository.save(shipment);
            return true;
        }
        if (effectiveStatus == ShipmentStatus.PENDING
                || effectiveStatus == ShipmentStatus.CONFIRMED
                || effectiveStatus == ShipmentStatus.IN_TRANSIT
                || effectiveStatus == ShipmentStatus.DELIVERED) {
            shipment.setNextBookingAttemptAt(null);
            shipment.setFailureReason(null);
            shipment.setUpdatedAt(Instant.now());
            shipmentRepository.save(shipment);
            synchronizeOrderStatus(order, shipment);
        } else if (effectiveStatus == ShipmentStatus.FAILED
                || effectiveStatus == ShipmentStatus.CANCELLED) {
            shipment.setFailureReason("Carrier reports a terminal shipment state");
            shipment.setNextBookingAttemptAt(null);
            shipment.setUpdatedAt(Instant.now());
            shipmentRepository.save(shipment);
            statusService.transition(order, OrderStatus.MANUAL_REVIEW, shipment.getFailureReason());
            orderRepository.save(order);
        } else {
            deferBookingPreflight(shipment, "Shipping provider state is not safe to book");
        }
        return false;
    }

    private void deferBookingPreflight(OrderShipment shipment, String reason) {
        shipment.setFailureReason(reason);
        shipment.setNextBookingAttemptAt(Instant.now().plus(bookingRetryDelay));
        shipment.setUpdatedAt(Instant.now());
        shipmentRepository.save(shipment);
    }

    private Duration retryDelay(int attempt) {
        return bookingRetryDelay.multipliedBy(Math.max(1, attempt));
    }

    @Transactional
    public ShippingStatusResponse refreshTracking(Long orderId) {
        Order order = requireOrderForUpdate(orderId);
        orderService.requireOwner(order);
        OrderShipment shipment = shipmentRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found"));
        refreshTracking(order, shipment);
        return shippingStatus(shipment, selectedOrUsedQuote(orderId));
    }

    @Transactional
    public void reconcileChowdeckTracking(Long orderId) {
        Order order = requireOrderForUpdate(orderId);
        OrderShipment shipment = shipmentRepository.findByOrderIdForUpdate(orderId).orElse(null);
        if (shipment == null
                || !isChowdeck(shipment.getProvider())
                || !ACTIVE_TRACKING_STATUSES.contains(shipment.getStatus())) {
            return;
        }
        refreshTracking(order, shipment);
    }

    private void refreshTracking(Order order, OrderShipment shipment) {
        ShippingProvider shipmentProvider = shippingRoutingService.require(shipment.getProvider());
        ShipmentTrackingStatus tracking = requireTrackingStatus(
                shipment.getProviderShipmentId(),
                shipmentProvider.trackShipment(shipment.getProviderShipmentId())
        );
        boolean accepted = applyProviderStatus(shipment, tracking.currentStatus(), tracking.lastUpdated());
        if (accepted || !hasText(shipment.getTrackingUrl())) {
            applyTrackingDetails(shipment, tracking, accepted);
        }
        shipment.setUpdatedAt(Instant.now());
        shipmentRepository.save(shipment);
        boolean pickupEvidence = hasPickupEvidence(tracking);
        if (pickupEvidence) {
            settlementService.recordCarrierPickupEvidence(order, shipment, pickupEvidenceAt(tracking));
        }
        if (accepted || pickupEvidence) {
            synchronizeOrderStatus(order, shipment);
        }
    }

    @Transactional
    public ShippingStatusResponse cancelBeforePayment(Long orderId) {
        Order order = requireOrderForUpdate(orderId);
        orderService.requireOwner(order);
        if (order.getPaymentStatus() == PaymentStatus.CONFIRMED) {
            throw new BusinessException("Paid orders require manual refund review before cancellation");
        }
        if (paymentReceiptRepository.existsByOrder_IdAndProviderStatus(
                orderId,
                com.example.shopsecure.provider.payment.PaymentTransactionStatus.SUCCESS
        )) {
            throw new BusinessException("Orders with received funds require manual refund review");
        }
        if (order.getStatus() == OrderStatus.AWAITING_PAYMENT
                && order.getFundingMode() == OrderFundingMode.DIRECT_DVA) {
            throw new BusinessException(
                    "A selected payable order requires manual review before cancellation"
            );
        }
        if (order.getStatus() == OrderStatus.AWAITING_PAYMENT
                && order.getFundingMode() == OrderFundingMode.PAYSTACK_CHECKOUT
                && paymentAttemptRepository.existsByOrder_Id(orderId)) {
            throw new BusinessException(
                    "An initialized checkout requires manual review before cancellation"
            );
        }
        if (order.getStatus() != OrderStatus.AWAITING_COUNTERPARTY
                && order.getStatus() != OrderStatus.AWAITING_SHIPPING_RATE
                && order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            throw new BusinessException("Order can no longer be cancelled directly");
        }
        OrderShipment shipment = shipmentRepository.findByOrderIdForUpdate(orderId).orElse(null);
        if (shipment != null && shipment.getProviderShipmentId() != null) {
            ShippingProvider shipmentProvider = shippingRoutingService.require(shipment.getProvider());
            if (shipment.getStatus() == ShipmentStatus.DRAFT && isChowdeck(shipment.getProvider())) {
                shipment.setStatus(ShipmentStatus.CANCELLED);
                shipment.setUpdatedAt(Instant.now());
                shipmentRepository.save(shipment);
            } else {
                ShipmentResponse cancellationResponse = isChowdeck(shipment.getProvider())
                        ? shipmentProvider.cancelShipment(
                                shipment.getProviderShipmentId(),
                                "Order cancelled before payment"
                        )
                        : shipmentProvider.cancelShipment(shipment.getProviderShipmentId());
                applyShipmentResponse(
                        shipment,
                        cancellationResponse
                );
            }
        }
        statusService.transition(order, OrderStatus.CANCELLED, "Order cancelled before payment");
        orderRepository.save(order);
        return shipment == null ? null : shippingStatus(shipment, selectedOrUsedQuote(orderId));
    }

    @Transactional
    public void handleWebhook(WebhookPayload payload) {
        ShipmentStatusUpdatedEvent event = shippingProvider.handleWebhookEvent(payload);
        if (!event.relevant()) {
            return;
        }
        applyWebhookEvent(event, payloadHash(payload.rawBody()));
    }

    @Transactional
    public void applyWebhookEvent(ShipmentStatusUpdatedEvent event, String eventKey) {
        if (!event.relevant()) {
            return;
        }
        if (event.occurredAt() == null) {
            throw new BusinessException("Terminal Africa webhook timestamp is required");
        }
        String dedupeKey = boundedEventKey(eventKey);
        if (webhookEventRepository.existsByProviderAndEventKey("TERMINAL_AFRICA", dedupeKey)) {
            return;
        }
        OrderShipment located = shipmentRepository.findByProviderShipmentId(event.shipmentId())
                .orElse(null);
        if (located == null || isChowdeck(located.getProvider())) {
            return;
        }
        Order order = requireOrderForUpdate(located.getOrder().getId());
        OrderShipment shipment = shipmentRepository.findByOrderIdForUpdate(order.getId()).orElse(null);
        if (shipment == null || !event.shipmentId().equals(shipment.getProviderShipmentId())) {
            return;
        }
        if (webhookEventRepository.existsByProviderAndEventKey("TERMINAL_AFRICA", dedupeKey)) {
            return;
        }
        boolean pickupEvidence = pickupConfirmed(event.status());
        if (pickupEvidence) {
            settlementService.recordCarrierPickupEvidence(order, shipment, event.occurredAt());
        }
        boolean accepted = applyProviderStatus(shipment, event.status(), event.occurredAt());
        if (accepted) {
            shipment.setUpdatedAt(Instant.now());
            shipmentRepository.save(shipment);
        }
        if (accepted || pickupEvidence) {
            synchronizeOrderStatus(order, shipment);
        }
        webhookEventRepository.save(ProcessedWebhookEvent.builder()
                .provider("TERMINAL_AFRICA")
                .eventKey(dedupeKey)
                .createdAt(Instant.now())
                .build());
    }

    private void resetExpiredPaymentSelection(Order order, Instant now) {
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT
                || order.getPaymentStatus() != PaymentStatus.PENDING) {
            return;
        }
        ShippingQuote selected = quoteRepository.findByShipment_Order_IdAndStatus(
                order.getId(),
                ShippingQuoteStatus.SELECTED
        ).orElse(null);
        if (selected == null || selected.getExpiresAt().isAfter(now)) {
            return;
        }

        boolean capturedPayment = paymentReceiptRepository.existsByOrder_IdAndProviderStatus(
                order.getId(),
                com.example.shopsecure.provider.payment.PaymentTransactionStatus.SUCCESS
        ) || paymentAttemptRepository.findFirstByOrder_IdAndStatusOrderByCreatedAtDesc(
                order.getId(),
                PaymentAttemptStatus.SUCCESS
        ).isPresent();
        boolean checkoutOutcomeUnknown = paymentAttemptRepository
                .findFirstByOrder_IdAndStatusOrderByCreatedAtDesc(
                        order.getId(),
                        PaymentAttemptStatus.INITIALIZED
                )
                .isPresent();
        if (order.getFundedAt() != null
                || order.getPaymentStatus() == PaymentStatus.CONFIRMED
                || capturedPayment
                || checkoutOutcomeUnknown) {
            statusService.transition(
                    order,
                    OrderStatus.MANUAL_REVIEW,
                    "Expired shipping quote has a captured or unreconciled payment"
            );
            orderRepository.save(order);
            throw new PaymentReviewRequiredException("Expired order quote requires payment review");
        }

        selected.setStatus(ShippingQuoteStatus.EXPIRED);
        quoteRepository.save(selected);
        List.of(PaymentAttemptStatus.FAILED, PaymentAttemptStatus.ABANDONED)
                .forEach(status -> paymentAttemptRepository.findByOrder_IdAndStatus(
                        order.getId(),
                        status
                ).forEach(attempt -> {
                    attempt.setStatus(PaymentAttemptStatus.SUPERSEDED);
                    attempt.setUpdatedAt(now);
                    paymentAttemptRepository.save(attempt);
                }));
        order.setProviderDeliveryFee(null);
        order.setDeliveryMarkup(null);
        order.setDeliveryFee(null);
        order.setServiceFee(null);
        order.setTotalAmount(null);
        order.setPaymentReference(null);
        order.setPaymentStatus(PaymentStatus.NOT_STARTED);
        statusService.transition(
                order,
                OrderStatus.AWAITING_SHIPPING_RATE,
                "Expired shipping quote reset for fresh rates"
        );
        orderRepository.save(order);
    }

    private List<ShippingQuoteResponse> createQuotes(Order order) {
        if (order.getStatus() != OrderStatus.AWAITING_SHIPPING_RATE) {
            throw new BusinessException("Order is not awaiting a shipping quote");
        }
        OrderCounterparty counterparty = counterpartyRepository.findByOrder_Id(order.getId())
                .orElseThrow(() -> new BusinessException("Counterparty details are required"));
        OrderParcel parcel = parcelRepository.findByOrder_Id(order.getId())
                .orElseThrow(() -> new BusinessException("Vendor parcel details are required"));

        OrderShipment existingShipment = shipmentRepository.findByOrder_Id(order.getId()).orElse(null);
        if (existingShipment != null) {
            List<ShippingQuote> available = quoteRepository.findByShipment_IdAndStatusOrderByDeliveryFeeAsc(
                    existingShipment.getId(),
                    ShippingQuoteStatus.AVAILABLE
            );
            Instant now = Instant.now();
            available.stream()
                    .filter(quote -> !quote.getExpiresAt().isAfter(now))
                    .forEach(quote -> {
                        quote.setStatus(ShippingQuoteStatus.EXPIRED);
                        quoteRepository.save(quote);
                    });
            List<ShippingQuote> cached = available.stream()
                    .filter(quote -> quote.getExpiresAt().isAfter(now))
                    .toList();
            if (!cached.isEmpty()) {
                return cached.stream().map(this::quoteResponse).toList();
            }
        }

        ShipmentRequest request = shipmentRequest(order, counterparty, parcel);
        validateAddress(request.pickupAddress(), "pickup");
        validateAddress(request.deliveryAddress(), "delivery");

        OrderShipment shipment = existingShipment;
        ShippingProvider.ShippingQuoteResult quoteResult;
        if (shipment == null) {
            ShippingProviderRoutingService.PreparedShipment prepared = shippingRoutingService.prepare(
                    request,
                    order.getCurrency()
            );
            ShipmentResponse response = requireShipmentResponse(prepared.preparation().shipment());
            Instant now = Instant.now();
            shipment = shipmentRepository.save(OrderShipment.builder()
                    .order(order)
                    .provider(prepared.providerCode())
                    .providerShipmentId(response.shipmentId())
                    .status(response.status())
                    .trackingReference(response.trackingReference())
                    .trackingUrl(TrackingUrl.normalizeHttps(response.trackingUrl()))
                    .providerUpdatedAt(response.updatedAt())
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            quoteResult = prepared.preparation().quoteResult();
        } else {
            quoteResult = shippingRoutingService.quote(
                    shipment.getProvider(),
                    shipment.getProviderShipmentId(),
                    request,
                    order.getCurrency()
            );
        }

        List<ShippingRateOption> rates = quoteResult == null ? null : quoteResult.rates();
        if (rates == null || rates.isEmpty()) {
            throw new BusinessException("No shipping rates are currently available");
        }
        Instant expiresAt = quoteResult.expiresAt() == null
                ? Instant.now().plus(quoteTtl)
                : quoteResult.expiresAt();
        OrderShipment persistedShipment = shipment;
        return rates.stream()
                .map(rate -> persistQuote(order, persistedShipment, rate, expiresAt))
                .sorted((left, right) -> left.deliveryFee().compareTo(right.deliveryFee()))
                .toList();
    }

    private ShippingQuoteResponse persistQuote(
            Order order,
            OrderShipment shipment,
            ShippingRateOption rate,
            Instant expiresAt
    ) {
        if (!order.getCurrency().equalsIgnoreCase(rate.currency())) {
            throw new BusinessException("Shipping rate currency does not match the order");
        }
        OrderPricingService.Pricing pricing = pricingService.price(order, rate.providerAmount());
        ShippingQuote quote = quoteRepository
                .findByShipment_IdAndProviderRateId(shipment.getId(), rate.providerRateId())
                .orElseGet(() -> ShippingQuote.builder()
                        .shipment(shipment)
                        .providerRateId(rate.providerRateId())
                        .createdAt(Instant.now())
                        .build());
        quote.setProviderInternalId(rate.providerInternalId());
        quote.setCarrierName(rate.carrierName());
        quote.setCarrierLogo(rate.carrierLogo());
        quote.setServiceDescription(rate.serviceDescription());
        quote.setProviderAmount(pricing.providerFee());
        quote.setDeliveryMarkup(pricing.deliveryMarkup());
        quote.setDeliveryFee(pricing.deliveryFee());
        quote.setServiceFee(pricing.serviceFee());
        quote.setBuyerTotal(pricing.total());
        quote.setCurrency(pricing.currency());
        quote.setPickupEstimate(rate.pickupEstimate());
        quote.setDeliveryEstimate(rate.deliveryEstimate());
        quote.setStatus(ShippingQuoteStatus.AVAILABLE);
        quote.setSelectedAt(null);
        quote.setExpiresAt(expiresAt);
        quote = quoteRepository.save(quote);
        return quoteResponse(quote);
    }

    private ShippingSelectionResponse selectQuote(Order order, Long quoteId) {
        if (order.getStatus() != OrderStatus.AWAITING_SHIPPING_RATE) {
            throw new BusinessException("Order is not awaiting quote selection");
        }
        ShippingQuote selected = quoteRepository.findByIdAndOrderIdForUpdate(quoteId, order.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Shipping quote not found"));
        if (selected.getStatus() != ShippingQuoteStatus.AVAILABLE) {
            throw new BusinessException("Shipping quote is no longer available");
        }
        if (!selected.getExpiresAt().isAfter(Instant.now())) {
            selected.setStatus(ShippingQuoteStatus.EXPIRED);
            quoteRepository.save(selected);
            throw new BusinessException("Shipping quote has expired; request fresh rates");
        }
        quoteRepository.findByShipment_Id(selected.getShipment().getId()).forEach(quote -> {
            quote.setStatus(quote.getId().equals(selected.getId())
                    ? ShippingQuoteStatus.SELECTED
                    : ShippingQuoteStatus.EXPIRED);
            if (quote.getId().equals(selected.getId())) {
                quote.setSelectedAt(Instant.now());
            }
            quoteRepository.save(quote);
        });
        pricingService.freeze(order, selected);
        if (order.getFundingMode() != OrderFundingMode.PAYSTACK_CHECKOUT) {
            order.setPaymentReference(paymentReference(order.getId()));
        }
        order.setPaymentStatus(PaymentStatus.PENDING);
        statusService.transition(order, OrderStatus.AWAITING_PAYMENT, "Creator selected shipping quote");
        orderRepository.save(order);
        return new ShippingSelectionResponse(
                order.getStatus(),
                order.getFundingMode(),
                quoteResponse(selected),
                pricingResponse(order),
                order.getFundingMode() == OrderFundingMode.DIRECT_DVA
                        ? paymentInstructions(order)
                        : null,
                orderService.buyerPaymentLink(order)
        );
    }

    private ShipmentRequest shipmentRequest(
            Order order,
            OrderCounterparty counterparty,
            OrderParcel parcel
    ) {
        boolean creatorIsVendor = order.getCreatorRole() == CreatorRole.VENDOR;
        ShipmentAddress creatorAddress = new ShipmentAddress(
                order.getCreatorAddressLine1(),
                order.getCreatorAddressLine2(),
                order.getCreatorCity(),
                order.getCreatorState(),
                order.getCreatorCountry(),
                order.getCreatorPostalCode()
        );
        ShipmentAddress counterpartyAddress = new ShipmentAddress(
                counterparty.getAddressLine1(),
                counterparty.getAddressLine2(),
                counterparty.getCity(),
                counterparty.getState(),
                counterparty.getCountry(),
                counterparty.getPostalCode()
        );
        ShipmentContact creatorContact = new ShipmentContact(
                order.getCreatorFirstName(),
                order.getCreatorLastName(),
                order.getCreatorEmail(),
                order.getCreatorPhoneNumber()
        );
        ShipmentContact counterpartyContact = new ShipmentContact(
                counterparty.getFirstName(),
                counterparty.getLastName(),
                counterparty.getEmail(),
                counterparty.getPhoneNumber()
        );
        return new ShipmentRequest(
                creatorIsVendor ? creatorAddress : counterpartyAddress,
                creatorIsVendor ? counterpartyAddress : creatorAddress,
                new ParcelDetails(
                        parcel.getWeightKg(),
                        parcel.getLengthCm(),
                        parcel.getWidthCm(),
                        parcel.getHeightCm(),
                        order.getItemDescription(),
                        order.getItemDescription(),
                        order.getItemAmount(),
                        order.getCurrency(),
                        parcel.getQuantity(),
                        parcel.getParcelType()
                ),
                creatorIsVendor ? creatorContact : counterpartyContact,
                creatorIsVendor ? counterpartyContact : creatorContact,
                "order-" + order.getId(),
                creatorIsVendor ? ShipmentUserAction.SENDING : ShipmentUserAction.RECEIVING
        );
    }

    private void validateAddress(ShipmentAddress address, String label) {
        if (address == null || !"NG".equalsIgnoreCase(address.country())) {
            throw new BusinessException("Only Nigerian shipping addresses are supported");
        }
        AddressValidationResult result = shippingProvider.validateAddress(address);
        if (!result.valid()) {
            throw new BusinessException("The " + label + " address could not be validated");
        }
    }

    private PaymentInstructionsResponse paymentInstructions(Order order) {
        VirtualAccount account = virtualAccountRepository.findByUser_Id(order.getCreator().getId())
                .orElseThrow(() -> new BusinessException("Collection virtual account is not available"));
        return new PaymentInstructionsResponse(
                new VirtualAccountDetailsResponse(
                        account.getAccountNumber(),
                        account.getBankName(),
                        account.getAccountName(),
                        account.getExpiresAt()
                ),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getPaymentReference(),
                "SHOPSECURE " + order.getPaymentReference()
        );
    }

    private PricingBreakdownResponse pricingResponse(Order order) {
        return new PricingBreakdownResponse(
                order.getItemAmount(),
                order.getDeliveryFee(),
                order.getServiceFee(),
                order.getTotalAmount(),
                order.getCurrency()
        );
    }

    private ShippingQuoteResponse quoteResponse(ShippingQuote quote) {
        return new ShippingQuoteResponse(
                quote.getId(),
                quote.getCarrierName(),
                quote.getCarrierLogo(),
                quote.getServiceDescription(),
                quote.getDeliveryFee(),
                quote.getServiceFee(),
                quote.getBuyerTotal(),
                quote.getCurrency(),
                quote.getPickupEstimate(),
                quote.getDeliveryEstimate(),
                quote.getExpiresAt()
        );
    }

    private ShippingStatusResponse shippingStatus(OrderShipment shipment, ShippingQuote quote) {
        OrderTrackingService.TrackingLink trackingLink = trackingService.linkForOrder(
                shipment.getOrder().getId()
        );
        return new ShippingStatusResponse(
                shipment.getStatus(),
                quote == null ? null : quote.getCarrierName(),
                shipment.getTrackingReference(),
                shipment.getTrackingUrl(),
                trackingLink == null ? null : trackingLink.url(),
                trackingLink == null ? null : trackingLink.availableAt(),
                quote == null ? null : quote.getDeliveryEstimate(),
                shipment.getUpdatedAt()
        );
    }

    private void applyShipmentResponse(OrderShipment shipment, ShipmentResponse response) {
        shipment.setStatus(response.status());
        if (hasText(response.trackingReference())) {
            shipment.setTrackingReference(response.trackingReference());
        }
        String trackingUrl = TrackingUrl.normalizeHttps(response.trackingUrl());
        if (trackingUrl != null) {
            shipment.setTrackingUrl(trackingUrl);
        }
        shipment.setProviderUpdatedAt(response.updatedAt());
        shipment.setUpdatedAt(Instant.now());
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

    private boolean applyProviderStatus(
            OrderShipment shipment,
            ShipmentStatus target,
            Instant providerUpdatedAt
    ) {
        if (providerUpdatedAt != null
                && shipment.getProviderUpdatedAt() != null
                && !providerUpdatedAt.isAfter(shipment.getProviderUpdatedAt())) {
            return false;
        }
        if (!canAdvance(shipment.getStatus(), target)) {
            return false;
        }
        shipment.setStatus(target);
        if (providerUpdatedAt != null) {
            shipment.setProviderUpdatedAt(providerUpdatedAt);
        }
        return true;
    }

    private void applyTrackingDetails(
            OrderShipment shipment,
            ShipmentTrackingStatus tracking,
            boolean replaceExisting
    ) {
        if (hasText(tracking.trackingReference())
                && (replaceExisting || !hasText(shipment.getTrackingReference()))) {
            shipment.setTrackingReference(tracking.trackingReference());
        }
        String trackingUrl = TrackingUrl.normalizeHttps(tracking.trackingUrl());
        if (trackingUrl != null && (replaceExisting || !hasText(shipment.getTrackingUrl()))) {
            shipment.setTrackingUrl(trackingUrl);
        }
    }

    private void synchronizeOrderStatus(Order order, OrderShipment shipment) {
        ShipmentStatus shipmentStatus = shipment.getStatus();
        boolean changed = false;
        if ((shipmentStatus == ShipmentStatus.CANCELLED || shipmentStatus == ShipmentStatus.FAILED)
                && (order.getStatus() == OrderStatus.READY_FOR_DELIVERY
                || order.getStatus() == OrderStatus.SHIPMENT_BOOKING)) {
            changed = statusService.transition(
                    order,
                    OrderStatus.SHIPPING_FAILED,
                    "Carrier shipment failed or cancelled"
            );
        } else if ((shipmentStatus == ShipmentStatus.CANCELLED || shipmentStatus == ShipmentStatus.FAILED)
                && order.getEscrowReleasePolicy() == EscrowReleasePolicy.CARRIER_PICKUP
                && order.getCarrierPickupConfirmedAt() != null
                && (order.getStatus() == OrderStatus.PAYOUT_PENDING
                || order.getStatus() == OrderStatus.PAYOUT_FAILED
                || order.getStatus() == OrderStatus.IN_TRANSIT)) {
            changed = statusService.transition(
                    order,
                    OrderStatus.MANUAL_REVIEW,
                    "Carrier shipment failed after escrow release was triggered"
            );
        } else if (shipmentStatus == ShipmentStatus.PENDING
                || shipmentStatus == ShipmentStatus.CONFIRMED
                || shipmentStatus == ShipmentStatus.IN_TRANSIT
                || shipmentStatus == ShipmentStatus.DELIVERED) {
            if (order.getStatus() == OrderStatus.SHIPPING_FAILED) {
                changed = statusService.transition(
                        order,
                        OrderStatus.SHIPMENT_BOOKING,
                        "Carrier confirmed shipment recovery"
                );
            }
            if (order.getStatus() == OrderStatus.SHIPMENT_BOOKING) {
                changed |= statusService.transition(
                        order,
                        OrderStatus.READY_FOR_DELIVERY,
                        "Shipment recovered and active"
                );
            }
        }
        if (shipmentStatus == ShipmentStatus.DELIVERED
                && order.getStatus() == OrderStatus.IN_TRANSIT
                && order.getSettlementStatus() == SettlementStatus.COMPLETED) {
            order.setCompletedAt(Instant.now());
            changed |= statusService.transition(
                    order,
                    OrderStatus.COMPLETED,
                    "Carrier confirmed delivery"
            );
        }
        if (changed) {
            orderRepository.save(order);
        }
        if (pickupConfirmed(shipmentStatus)) {
            settlementService.recordCarrierPickup(order, shipment);
        }
    }

    private boolean pickupConfirmed(ShipmentStatus status) {
        return status == ShipmentStatus.IN_TRANSIT || status == ShipmentStatus.DELIVERED;
    }

    private boolean hasPickupEvidence(ShipmentTrackingStatus tracking) {
        return pickupConfirmed(tracking.currentStatus()) || tracking.history().stream()
                .map(ShipmentTrackingCheckpoint::status)
                .anyMatch(this::pickupConfirmed);
    }

    private Instant pickupEvidenceAt(ShipmentTrackingStatus tracking) {
        return tracking.history().stream()
                .filter(checkpoint -> pickupConfirmed(checkpoint.status()))
                .map(ShipmentTrackingCheckpoint::timestamp)
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(pickupConfirmed(tracking.currentStatus()) ? tracking.lastUpdated() : null);
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isChowdeck(String provider) {
        return provider != null && ShippingProviderCode.CHOWDECK.equalsIgnoreCase(provider);
    }

    private ShippingQuote selectedQuote(Long orderId) {
        return quoteRepository.findByShipment_Order_IdAndStatus(orderId, ShippingQuoteStatus.SELECTED)
                .orElseGet(() -> quoteRepository.findByShipment_Order_IdAndStatus(
                        orderId,
                        ShippingQuoteStatus.USED
                ).orElseThrow(() -> new BusinessException("Selected shipping quote was not found")));
    }

    private ShippingQuote selectedOrUsedQuote(Long orderId) {
        return quoteRepository.findByShipment_Order_IdAndStatus(orderId, ShippingQuoteStatus.SELECTED)
                .orElseGet(() -> quoteRepository.findByShipment_Order_IdAndStatus(
                        orderId,
                        ShippingQuoteStatus.USED
                ).orElse(null));
    }

    private Order requireOrderForUpdate(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    private String paymentReference(Long orderId) {
        return "ORD-" + orderId + "-" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 10)
                .toUpperCase(Locale.ROOT);
    }

    private String payloadHash(String body) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(body.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to fingerprint webhook", exception);
        }
    }

    private boolean canAdvance(ShipmentStatus current, ShipmentStatus target) {
        if (current == ShipmentStatus.FAILED) {
            return target == ShipmentStatus.PENDING
                    || target == ShipmentStatus.CONFIRMED
                    || target == ShipmentStatus.IN_TRANSIT
                    || target == ShipmentStatus.DELIVERED;
        }
        if (current == ShipmentStatus.CANCELLED) {
            return target == ShipmentStatus.CANCELLED;
        }
        if (current == ShipmentStatus.DELIVERED) {
            return target == ShipmentStatus.DELIVERED;
        }
        if (target == ShipmentStatus.CANCELLED || target == ShipmentStatus.FAILED) {
            return true;
        }
        return rank(target) >= rank(current);
    }

    private int rank(ShipmentStatus status) {
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

    private static final class PaymentReviewRequiredException extends BusinessException {
        private PaymentReviewRequiredException(String message) {
            super(message);
        }
    }
}
