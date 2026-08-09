package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.*;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;

@Service
@RequiredArgsConstructor
public class GuestOrderStatusService {

    private final GuestOrderAccessService guestAccessService;
    private final VirtualAccountRepository virtualAccountRepository;
    private final OrderShipmentRepository shipmentRepository;
    private final ShippingQuoteRepository quoteRepository;
    private final PaymentReceiptRepository receiptRepository;

    @Transactional(readOnly = true)
    public GuestOrderStatusResponse getStatus(String shareToken, String guestToken) {
        GuestOrderAccessToken access = guestAccessService.require(shareToken, guestToken);
        Order order = access.getOrder();
        OrderShipment shipment = shipmentRepository.findByOrder_Id(order.getId()).orElse(null);
        ShippingQuote quote = selectedOrUsedQuote(order.getId());
        java.math.BigDecimal amountReceived = receiptRepository.sumAmountByOrderAndClassificationIn(
                order.getId(),
                EnumSet.of(
                        PaymentReceiptClassification.UNDERPAID,
                        PaymentReceiptClassification.MATCHED,
                        PaymentReceiptClassification.OVERPAID
                )
        );
        return new GuestOrderStatusResponse(
                order.getStatus(),
                order.getFundingMode(),
                order.getPaymentStatus(),
                order.getSettlementStatus(),
                order.getTotalAmount(),
                amountReceived.signum() == 0 ? null : amountReceived,
                order.getCurrency(),
                order.getCreatorRole() == CreatorRole.VENDOR
                        && order.getFundingMode() == OrderFundingMode.DIRECT_DVA
                        && order.getStatus() == OrderStatus.AWAITING_PAYMENT
                        && order.getPaymentStatus() != PaymentStatus.OVERPAID
                        ? paymentInstructions(order)
                        : null,
                shipping(shipment, quote),
                order.getFundedAt(),
                order.getDeliveryConfirmedAt(),
                order.getCompletedAt()
        );
    }

    private PaymentInstructionsResponse paymentInstructions(Order order) {
        if (order.getTotalAmount() == null || order.getPaymentReference() == null) {
            return null;
        }
        VirtualAccount account = virtualAccountRepository.findByUser_Id(order.getCreator().getId())
                .orElse(null);
        if (account == null) {
            return null;
        }
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

    private ShippingStatusResponse shipping(OrderShipment shipment, ShippingQuote quote) {
        if (shipment == null) {
            return null;
        }
        return new ShippingStatusResponse(
                shipment.getStatus(),
                quote == null ? null : quote.getCarrierName(),
                shipment.getTrackingReference(),
                shipment.getTrackingUrl(),
                quote == null ? null : quote.getDeliveryEstimate(),
                shipment.getUpdatedAt()
        );
    }

    private ShippingQuote selectedOrUsedQuote(Long orderId) {
        return quoteRepository.findByShipment_Order_IdAndStatus(orderId, ShippingQuoteStatus.SELECTED)
                .orElseGet(() -> quoteRepository.findByShipment_Order_IdAndStatus(
                        orderId,
                        ShippingQuoteStatus.USED
                ).orElse(null));
    }
}
