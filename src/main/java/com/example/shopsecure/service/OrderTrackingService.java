package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.AddressResponse;
import com.example.shopsecure.dto.response.PricingBreakdownResponse;
import com.example.shopsecure.dto.response.PublicOrderTrackingResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.repository.OrderRepository;
import com.example.shopsecure.repository.OrderCounterpartyRepository;
import com.example.shopsecure.repository.OrderParcelRepository;
import com.example.shopsecure.repository.OrderSettlementRepository;
import com.example.shopsecure.repository.OrderShipmentRepository;
import com.example.shopsecure.repository.OrderTrackingAccessTokenRepository;
import com.example.shopsecure.repository.ShippingQuoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class OrderTrackingService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final OrderRepository orderRepository;
    private final OrderCounterpartyRepository counterpartyRepository;
    private final OrderParcelRepository parcelRepository;
    private final OrderSettlementRepository settlementRepository;
    private final OrderShipmentRepository shipmentRepository;
    private final ShippingQuoteRepository quoteRepository;
    private final OrderTrackingAccessTokenRepository accessTokenRepository;
    private final SensitiveDataCipher dataCipher;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    @Value("${order.tracking-token-ttl:P180D}")
    private Duration trackingTokenTtl;

    @Transactional(propagation = Propagation.MANDATORY)
    public OrderTrackingAccessToken activate(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (order.getPaymentStatus() != PaymentStatus.CONFIRMED || order.getFundedAt() == null) {
            throw new BusinessException("Order payment must be confirmed before tracking is activated");
        }
        OrderTrackingAccessToken existing = accessTokenRepository
                .findByOrderIdForUpdate(orderId)
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        String rawToken = generateUniqueToken();
        Instant now = Instant.now();
        return accessTokenRepository.save(OrderTrackingAccessToken.builder()
                .order(order)
                .tokenHash(hash(rawToken))
                .tokenCiphertext(dataCipher.encrypt(rawToken))
                .expiresAt(now.plus(validTtl()))
                .createdAt(now)
                .build());
    }

    @Transactional(readOnly = true)
    public TrackingLink linkForOrder(Long orderId) {
        OrderTrackingAccessToken access = accessTokenRepository.findByOrder_Id(orderId).orElse(null);
        if (!isActive(access, Instant.now())) {
            return null;
        }
        return new TrackingLink(frontendLink(dataCipher.decrypt(access.getTokenCiphertext())), access.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public Long requireActiveOrderId(String rawToken) {
        return requireActiveAccess(rawToken).getOrder().getId();
    }

    @Transactional(readOnly = true)
    public PublicOrderTrackingResponse getPublicTracking(String rawToken) {
        OrderTrackingAccessToken access = requireActiveAccess(rawToken);

        Order order = access.getOrder();
        OrderCounterparty counterparty = counterpartyRepository.findByOrder_Id(order.getId()).orElse(null);
        OrderParcel parcel = parcelRepository.findByOrder_Id(order.getId()).orElse(null);
        OrderSettlement settlement = settlementRepository.findByOrder_Id(order.getId()).orElse(null);
        OrderShipment shipment = shipmentRepository.findByOrder_Id(order.getId()).orElse(null);
        ShippingQuote quote = selectedOrUsedQuote(order.getId());
        PublicOrderTrackingResponse.Party creator = creatorParty(order);
        PublicOrderTrackingResponse.Party guest = counterpartyParty(order, counterparty);
        return new PublicOrderTrackingResponse(
                order.getId(),
                order.getItemDescription(),
                order.getItemAmount(),
                order.getCurrency(),
                order.getCreatorRole(),
                order.getFundingMode(),
                order.getStatus(),
                publicStatus(order.getStatus(), shipment == null ? null : shipment.getStatus()),
                order.getPaymentStatus(),
                order.getPaymentReference(),
                order.getCreatorRole() == CreatorRole.BUYER ? creator : guest,
                order.getCreatorRole() == CreatorRole.VENDOR ? creator : guest,
                payoutAccount(order),
                pricing(order),
                parcel(parcel),
                shipment(shipment, quote),
                escrow(order, settlement),
                order.getFundedAt(),
                order.getDeliveryConfirmedAt(),
                order.getCarrierPickupConfirmedAt(),
                order.getCompletedAt(),
                order.getCreatedAt(),
                access.getCreatedAt(),
                access.getExpiresAt(),
                latestUpdate(order, shipment, settlement)
        );
    }

    private PublicOrderTrackingResponse.Party creatorParty(Order order) {
        return new PublicOrderTrackingResponse.Party(
                order.getCreatorRole(),
                order.getCreatorFirstName(),
                order.getCreatorLastName(),
                order.getCreatorEmail(),
                order.getCreatorPhoneNumber(),
                new AddressResponse(
                        order.getCreatorAddressLine1(),
                        order.getCreatorAddressLine2(),
                        order.getCreatorCity(),
                        order.getCreatorState(),
                        order.getCreatorCountry(),
                        order.getCreatorPostalCode()
                )
        );
    }

    private PublicOrderTrackingResponse.Party counterpartyParty(
            Order order,
            OrderCounterparty counterparty
    ) {
        if (counterparty == null) {
            return null;
        }
        return new PublicOrderTrackingResponse.Party(
                order.getCreatorRole().opposite(),
                counterparty.getFirstName(),
                counterparty.getLastName(),
                counterparty.getEmail(),
                counterparty.getPhoneNumber(),
                new AddressResponse(
                        counterparty.getAddressLine1(),
                        counterparty.getAddressLine2(),
                        counterparty.getCity(),
                        counterparty.getState(),
                        counterparty.getCountry(),
                        counterparty.getPostalCode()
                )
        );
    }

    private PublicOrderTrackingResponse.PayoutAccount payoutAccount(Order order) {
        if (order.getVendorBankAccountLast4() == null) {
            return null;
        }
        return new PublicOrderTrackingResponse.PayoutAccount(
                order.getVendorBankAccountLast4(),
                order.getVendorBankCode(),
                order.getVendorBankName(),
                order.getVendorBankAccountName()
        );
    }

    private PricingBreakdownResponse pricing(Order order) {
        if (order.getTotalAmount() == null) {
            return null;
        }
        return new PricingBreakdownResponse(
                order.getItemAmount(),
                order.getDeliveryFee(),
                order.getServiceFee(),
                order.getTotalAmount(),
                order.getCurrency()
        );
    }

    private PublicOrderTrackingResponse.Parcel parcel(OrderParcel parcel) {
        if (parcel == null) {
            return null;
        }
        return new PublicOrderTrackingResponse.Parcel(
                parcel.getWeightKg(),
                parcel.getLengthCm(),
                parcel.getWidthCm(),
                parcel.getHeightCm(),
                parcel.getQuantity(),
                parcel.getParcelType()
        );
    }

    private PublicOrderTrackingResponse.Shipment shipment(
            OrderShipment shipment,
            ShippingQuote quote
    ) {
        if (shipment == null) {
            return null;
        }
        PublicOrderTrackingResponse.Quote selectedQuote = quote == null
                ? null
                : new PublicOrderTrackingResponse.Quote(
                        quote.getId(),
                        quote.getProviderRateId(),
                        quote.getProviderInternalId(),
                        quote.getCarrierName(),
                        quote.getCarrierLogo(),
                        quote.getServiceDescription(),
                        quote.getProviderAmount(),
                        quote.getDeliveryMarkup(),
                        quote.getDeliveryFee(),
                        quote.getServiceFee(),
                        quote.getBuyerTotal(),
                        quote.getCurrency(),
                        quote.getPickupEstimate(),
                        quote.getDeliveryEstimate(),
                        quote.getSelectedAt(),
                        quote.getExpiresAt()
                );
        return new PublicOrderTrackingResponse.Shipment(
                shipment.getProvider(),
                shipment.getProviderShipmentId(),
                shipment.getStatus(),
                shipment.getTrackingReference(),
                shipment.getTrackingUrl(),
                selectedQuote,
                shipment.getProviderUpdatedAt(),
                shipment.getUpdatedAt()
        );
    }

    private PublicOrderTrackingResponse.Escrow escrow(
            Order order,
            OrderSettlement settlement
    ) {
        SettlementStatus status = settlement == null ? order.getSettlementStatus() : settlement.getStatus();
        boolean released = status == SettlementStatus.COMPLETED;
        java.math.BigDecimal zero = java.math.BigDecimal.ZERO.setScale(order.getItemAmount().scale());
        return new PublicOrderTrackingResponse.Escrow(
                order.getEscrowReleasePolicy(),
                status,
                settlement == null ? null : settlement.getSettlementType(),
                settlement == null ? null : settlement.getTrigger(),
                order.getItemAmount(),
                released ? zero : order.getItemAmount(),
                released ? order.getItemAmount() : zero,
                order.getCurrency(),
                settlement == null ? null : settlement.getPayoutReference(),
                settlement == null ? null : settlement.getTransferId(),
                settlement == null ? null : settlement.getFailureReason(),
                settlement == null ? null : settlement.getTriggeredAt(),
                settlement == null ? null : settlement.getCompletedAt()
        );
    }

    private Instant latestUpdate(
            Order order,
            OrderShipment shipment,
            OrderSettlement settlement
    ) {
        Instant latest = order.getUpdatedAt();
        if (shipment != null && shipment.getUpdatedAt() != null
                && (latest == null || shipment.getUpdatedAt().isAfter(latest))) {
            latest = shipment.getUpdatedAt();
        }
        if (settlement != null && settlement.getUpdatedAt() != null
                && (latest == null || settlement.getUpdatedAt().isAfter(latest))) {
            latest = settlement.getUpdatedAt();
        }
        return latest;
    }

    private ShippingQuote selectedOrUsedQuote(Long orderId) {
        return quoteRepository.findByShipment_Order_IdAndStatus(orderId, ShippingQuoteStatus.SELECTED)
                .orElseGet(() -> quoteRepository.findByShipment_Order_IdAndStatus(
                        orderId,
                        ShippingQuoteStatus.USED
                ).orElse(null));
    }

    private PublicOrderTrackingResponse.Status publicStatus(
            OrderStatus orderStatus,
            ShipmentStatus status
    ) {
        if (orderStatus == OrderStatus.MANUAL_REVIEW
                && (status == null || status == ShipmentStatus.DRAFT || status == ShipmentStatus.UNKNOWN)) {
            return PublicOrderTrackingResponse.Status.EXCEPTION;
        }
        if (status == null) {
            return PublicOrderTrackingResponse.Status.BOOKING;
        }
        return switch (status) {
            case DRAFT, PENDING -> PublicOrderTrackingResponse.Status.BOOKING;
            case CONFIRMED -> PublicOrderTrackingResponse.Status.AWAITING_PICKUP;
            case IN_TRANSIT -> PublicOrderTrackingResponse.Status.IN_TRANSIT;
            case DELIVERED -> PublicOrderTrackingResponse.Status.DELIVERED;
            case FAILED, UNKNOWN -> PublicOrderTrackingResponse.Status.EXCEPTION;
            case CANCELLED -> PublicOrderTrackingResponse.Status.CANCELLED;
        };
    }

    private String generateUniqueToken() {
        for (int attempt = 0; attempt < 5; attempt++) {
            byte[] bytes = new byte[32];
            secureRandom.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (!accessTokenRepository.existsByTokenHash(hash(token))) {
                return token;
            }
        }
        throw new BusinessException("Unable to create a secure tracking link");
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to protect tracking token", exception);
        }
    }

    private boolean isActive(OrderTrackingAccessToken access, Instant now) {
        return access != null
                && access.getRevokedAt() == null
                && access.getExpiresAt() != null
                && access.getExpiresAt().isAfter(now);
    }

    private OrderTrackingAccessToken requireActiveAccess(String rawToken) {
        if (rawToken == null || !TOKEN_PATTERN.matcher(rawToken).matches()) {
            throw trackingNotFound();
        }
        OrderTrackingAccessToken access = accessTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(this::trackingNotFound);
        if (!isActive(access, Instant.now())) {
            throw trackingNotFound();
        }
        return access;
    }

    private Duration validTtl() {
        if (trackingTokenTtl == null || trackingTokenTtl.isZero() || trackingTokenTtl.isNegative()) {
            throw new IllegalStateException("Order tracking token TTL must be positive");
        }
        return trackingTokenTtl;
    }

    private String frontendLink(String rawToken) {
        return frontendBaseUrl.replaceAll("/+$", "") + "/orders/tracking/" + rawToken;
    }

    private ResourceNotFoundException trackingNotFound() {
        return new ResourceNotFoundException("Tracking link not found");
    }

    public record TrackingLink(String url, Instant availableAt) {
    }
}
