package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.*;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.provider.common.WebhookPayload;
import com.example.shopsecure.provider.shipping.*;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
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
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderShippingService {

    private final ShippingProvider shippingProvider;
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
    private final GuestOrderAccessService guestAccessService;

    @Value("${shipping.terminal-africa.enabled:false}")
    private boolean terminalEnabled;

    @Value("${shipping.quote-ttl:PT15M}")
    private Duration quoteTtl;

    @Value("${shipping.booking-reconciliation.max-attempts:5}")
    private int maxBookingAttempts;

    @Value("${shipping.booking-reconciliation.retry-delay:PT5M}")
    private Duration bookingRetryDelay;

    @Transactional
    public List<ShippingQuoteResponse> createCreatorQuotes(Long orderId) {
        Order order = requireOrderForUpdate(orderId);
        orderService.requireOwner(order);
        if (order.getCreatorRole() != CreatorRole.BUYER) {
            throw new AccessDeniedException("Only the buyer can request shipping quotes");
        }
        return createQuotes(order);
    }

    @Transactional
    public List<ShippingQuoteResponse> createGuestQuotes(String shareToken, String guestToken) {
        GuestOrderAccessToken access = guestAccessService.require(shareToken, guestToken);
        Order order = requireOrderForUpdate(access.getOrder().getId());
        if (order.getCreatorRole() != CreatorRole.VENDOR) {
            throw new AccessDeniedException("Only the buyer can request shipping quotes");
        }
        return createQuotes(order);
    }

    @Transactional
    public ShippingSelectionResponse selectCreatorQuote(Long orderId, Long quoteId) {
        Order order = requireOrderForUpdate(orderId);
        orderService.requireOwner(order);
        if (order.getCreatorRole() != CreatorRole.BUYER) {
            throw new AccessDeniedException("Only the buyer can select a shipping quote");
        }
        return selectQuote(order, quoteId);
    }

    @Transactional
    public ShippingSelectionResponse selectGuestQuote(
            String shareToken,
            String guestToken,
            Long quoteId
    ) {
        GuestOrderAccessToken access = guestAccessService.require(shareToken, guestToken);
        Order order = requireOrderForUpdate(access.getOrder().getId());
        if (order.getCreatorRole() != CreatorRole.VENDOR) {
            throw new AccessDeniedException("Only the buyer can select a shipping quote");
        }
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
        if (!providerDraftCanBeBooked(order, shipment)) {
            return;
        }
        if (order.getStatus() == OrderStatus.SHIPPING_FAILED) {
            statusService.transition(order, OrderStatus.SHIPMENT_BOOKING, "Retrying shipment booking");
        }

        if (shipment.getBookingAttempt() >= maxBookingAttempts) {
            shipment.setFailureReason("Automated shipment booking retry limit reached");
            shipment.setNextBookingAttemptAt(null);
            shipment.setUpdatedAt(Instant.now());
            shipmentRepository.save(shipment);
            statusService.transition(order, OrderStatus.MANUAL_REVIEW, shipment.getFailureReason());
            orderRepository.save(order);
            return;
        }
        ShippingQuote quote = selectedQuote(orderId);
        shipment.setBookingAttempt(shipment.getBookingAttempt() + 1);
        shipment.setNextBookingAttemptAt(null);
        shipment.setFailureReason(null);
        shipment.setUpdatedAt(Instant.now());
        shipmentRepository.save(shipment);
        try {
            BigDecimal providerWalletBalance = shippingProvider.getWalletBalance();
            if (providerWalletBalance != null
                    && providerWalletBalance.compareTo(quote.getProviderAmount()) < 0) {
                throw new BusinessException("Shipping provider wallet requires funding before booking");
            }
            ShipmentResponse response = shippingProvider.arrangePickup(
                    shipment.getProviderShipmentId(),
                    quote.getProviderRateId()
            );
            if (response.status() != ShipmentStatus.CONFIRMED
                    && response.status() != ShipmentStatus.PENDING) {
                throw new BusinessException("Shipping provider did not confirm the booking");
            }
            applyShipmentResponse(shipment, response);
            shipment.setNextBookingAttemptAt(null);
            shipment.setFailureReason(null);
            quote.setStatus(ShippingQuoteStatus.USED);
            quoteRepository.save(quote);
            statusService.transition(order, OrderStatus.READY_FOR_DELIVERY, "Shipment pickup arranged");
        } catch (RuntimeException exception) {
            shipment.setStatus(ShipmentStatus.FAILED);
            shipment.setFailureReason("Shipment booking attempt failed");
            if (shipment.getBookingAttempt() >= maxBookingAttempts) {
                shipment.setNextBookingAttemptAt(null);
                statusService.transition(order, OrderStatus.MANUAL_REVIEW,
                        "Automated shipment booking retry limit reached");
            } else {
                shipment.setNextBookingAttemptAt(Instant.now().plus(retryDelay(shipment.getBookingAttempt())));
                statusService.transition(order, OrderStatus.SHIPPING_FAILED, "Shipment booking failed");
            }
            shipment.setUpdatedAt(Instant.now());
            shipmentRepository.save(shipment);
            log.error("Shipment booking failed: orderId={}", orderId);
        }
        orderRepository.save(order);
    }

    private boolean providerDraftCanBeBooked(Order order, OrderShipment shipment) {
        ShipmentTrackingStatus tracking;
        try {
            tracking = shippingProvider.trackShipment(shipment.getProviderShipmentId());
        } catch (RuntimeException exception) {
            deferBookingPreflight(shipment, "Unable to verify shipment state before booking");
            log.warn("Unable to verify shipment before booking: orderId={}", order.getId());
            return false;
        }
        if (tracking == null || tracking.currentStatus() == null) {
            deferBookingPreflight(shipment, "Shipping provider returned no tracking state");
            return false;
        }
        if (tracking.currentStatus() == ShipmentStatus.DRAFT) {
            shipment.setStatus(ShipmentStatus.DRAFT);
            shipment.setProviderUpdatedAt(tracking.lastUpdated());
            shipment.setNextBookingAttemptAt(null);
            shipment.setFailureReason(null);
            shipment.setUpdatedAt(Instant.now());
            shipmentRepository.save(shipment);
            return true;
        }
        if (tracking.currentStatus() == ShipmentStatus.PENDING
                || tracking.currentStatus() == ShipmentStatus.CONFIRMED
                || tracking.currentStatus() == ShipmentStatus.IN_TRANSIT
                || tracking.currentStatus() == ShipmentStatus.DELIVERED) {
            shipment.setStatus(tracking.currentStatus());
            shipment.setProviderUpdatedAt(tracking.lastUpdated());
            shipment.setNextBookingAttemptAt(null);
            shipment.setFailureReason(null);
            shipment.setUpdatedAt(Instant.now());
            shipmentRepository.save(shipment);
            if (order.getStatus() == OrderStatus.SHIPPING_FAILED) {
                statusService.transition(order, OrderStatus.SHIPMENT_BOOKING,
                        "Carrier confirmed shipment recovery");
            }
            statusService.transition(order, OrderStatus.READY_FOR_DELIVERY, "Shipment recovered and active");
            orderRepository.save(order);
        } else if (tracking.currentStatus() == ShipmentStatus.FAILED
                || tracking.currentStatus() == ShipmentStatus.CANCELLED) {
            shipment.setStatus(tracking.currentStatus());
            shipment.setProviderUpdatedAt(tracking.lastUpdated());
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
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        orderService.requireOwner(order);
        OrderShipment shipment = shipmentRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found"));
        ShipmentTrackingStatus tracking = shippingProvider.trackShipment(shipment.getProviderShipmentId());
        if (canAdvance(shipment.getStatus(), tracking.currentStatus())) {
            shipment.setStatus(tracking.currentStatus());
            shipment.setProviderUpdatedAt(tracking.lastUpdated());
        }
        shipment.setUpdatedAt(Instant.now());
        shipmentRepository.save(shipment);
        return shippingStatus(shipment, selectedOrUsedQuote(orderId));
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
            applyShipmentResponse(
                    shipment,
                    shippingProvider.cancelShipment(shipment.getProviderShipmentId())
            );
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
        String eventKey = event.shipmentId() + ":" + event.status() + ":" + payloadHash(payload.rawBody());
        if (webhookEventRepository.existsByProviderAndEventKey("TERMINAL_AFRICA", eventKey)) {
            return;
        }
        OrderShipment shipment = shipmentRepository.findByProviderShipmentIdForUpdate(event.shipmentId())
                .orElse(null);
        if (shipment == null) {
            return;
        }
        if (canAdvance(shipment.getStatus(), event.status())) {
            shipment.setStatus(event.status());
            shipment.setProviderUpdatedAt(event.occurredAt());
            shipment.setUpdatedAt(Instant.now());
            shipmentRepository.save(shipment);
            Order order = orderRepository.findByIdForUpdate(shipment.getOrder().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
            if ((event.status() == ShipmentStatus.CANCELLED || event.status() == ShipmentStatus.FAILED)
                    && (order.getStatus() == OrderStatus.READY_FOR_DELIVERY
                    || order.getStatus() == OrderStatus.SHIPMENT_BOOKING)) {
                statusService.transition(order, OrderStatus.SHIPPING_FAILED, "Carrier shipment failed or cancelled");
                orderRepository.save(order);
            } else if ((event.status() == ShipmentStatus.CONFIRMED
                    || event.status() == ShipmentStatus.IN_TRANSIT
                    || event.status() == ShipmentStatus.DELIVERED)
                    && order.getStatus() == OrderStatus.SHIPPING_FAILED) {
                statusService.transition(order, OrderStatus.SHIPMENT_BOOKING, "Carrier confirmed shipment recovery");
                statusService.transition(order, OrderStatus.READY_FOR_DELIVERY, "Shipment recovered and active");
                orderRepository.save(order);
            }
        }
        webhookEventRepository.save(ProcessedWebhookEvent.builder()
                .provider("TERMINAL_AFRICA")
                .eventKey(eventKey)
                .createdAt(Instant.now())
                .build());
    }

    private List<ShippingQuoteResponse> createQuotes(Order order) {
        if (order.getStatus() != OrderStatus.AWAITING_SHIPPING_RATE) {
            throw new BusinessException("Order is not awaiting a shipping quote");
        }
        OrderCounterparty counterparty = counterpartyRepository.findByOrder_Id(order.getId())
                .filter(OrderCounterparty::isPhoneVerified)
                .orElseThrow(() -> new BusinessException("Counterparty must verify their details first"));
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
        if (shipment == null) {
            ShipmentResponse response = shippingProvider.createQuickShipment(request);
            Instant now = Instant.now();
            shipment = shipmentRepository.save(OrderShipment.builder()
                    .order(order)
                    .provider(terminalEnabled ? "TERMINAL_AFRICA" : "STUB")
                    .providerShipmentId(response.shipmentId())
                    .status(response.status())
                    .trackingReference(response.trackingReference())
                    .trackingUrl(response.trackingUrl())
                    .providerUpdatedAt(response.updatedAt())
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }

        List<ShippingRateOption> rates = shippingProvider.getRates(
                shipment.getProviderShipmentId(),
                order.getCurrency()
        );
        if (rates.isEmpty()) {
            throw new BusinessException("No shipping rates are currently available");
        }
        OrderShipment persistedShipment = shipment;
        return rates.stream()
                .map(rate -> persistQuote(order, persistedShipment, rate))
                .sorted((left, right) -> left.deliveryFee().compareTo(right.deliveryFee()))
                .toList();
    }

    private ShippingQuoteResponse persistQuote(
            Order order,
            OrderShipment shipment,
            ShippingRateOption rate
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
        quote.setExpiresAt(Instant.now().plus(quoteTtl));
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
        statusService.transition(order, OrderStatus.AWAITING_PAYMENT, "Buyer selected shipping quote");
        orderRepository.save(order);
        return new ShippingSelectionResponse(
                order.getStatus(),
                order.getFundingMode(),
                quoteResponse(selected),
                pricingResponse(order),
                order.getFundingMode() == OrderFundingMode.DIRECT_DVA
                        ? paymentInstructions(order)
                        : null
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
                "order-" + order.getId()
        );
    }

    private void validateAddress(ShipmentAddress address, String label) {
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
                quote.getDeliveryEstimate()
        );
    }

    private ShippingStatusResponse shippingStatus(OrderShipment shipment, ShippingQuote quote) {
        return new ShippingStatusResponse(
                shipment.getStatus(),
                quote == null ? null : quote.getCarrierName(),
                shipment.getTrackingReference(),
                shipment.getTrackingUrl(),
                quote == null ? null : quote.getDeliveryEstimate(),
                shipment.getUpdatedAt()
        );
    }

    private void applyShipmentResponse(OrderShipment shipment, ShipmentResponse response) {
        shipment.setStatus(response.status());
        shipment.setTrackingReference(response.trackingReference());
        shipment.setTrackingUrl(response.trackingUrl());
        shipment.setProviderUpdatedAt(response.updatedAt());
        shipment.setUpdatedAt(Instant.now());
        shipmentRepository.save(shipment);
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
}
