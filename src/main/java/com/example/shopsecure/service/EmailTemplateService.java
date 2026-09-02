package com.example.shopsecure.service;

import com.example.shopsecure.entity.CreatorRole;
import com.example.shopsecure.entity.EscrowReleasePolicy;
import com.example.shopsecure.entity.SettlementStatus;
import com.example.shopsecure.entity.SettlementType;
import com.example.shopsecure.entity.SettlementTrigger;
import com.example.shopsecure.entity.OrderDisputeNotificationAudience;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.entity.WaybillPartyRole;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class EmailTemplateService {

    private static final DateTimeFormatter WAYBILL_EVENT_TIME = DateTimeFormatter.ofPattern(
            "d MMM uuuu 'at' HH:mm 'UTC'",
            Locale.ENGLISH
    );

    public String orderTrackingReadyBody(
            String firstName,
            CreatorRole participantRole,
            String trackingUrl
    ) {
        String safeName = HtmlUtils.htmlEscape(
                firstName == null || firstName.isBlank() ? "there" : firstName
        );
        String safeRole = HtmlUtils.htmlEscape(
                participantRole == CreatorRole.BUYER ? "Buyer" : "Vendor"
        );
        String safeTrackingUrl = HtmlUtils.htmlEscape(trackingUrl);
        return """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #16a34a;">Your order tracking page is ready</h2>
                    <p>Hi <strong>%s</strong>,</p>
                    <p>Payment is confirmed and ShopSecure is arranging the shipment.</p>
                    <div style="background: #f8fafc; border-radius: 8px; padding: 16px; margin: 16px 0;">
                        <p><strong>Your role:</strong> %s</p>
                        <p><strong>Current stage:</strong> Shipment booking</p>
                    </div>
                    <p>
                        <a href="%s" target="_blank" rel="noopener noreferrer"
                           style="display: inline-block; background: #16a34a; color: #ffffff; padding: 12px 18px; border-radius: 6px; text-decoration: none;">
                            Track order
                        </a>
                    </p>
                    <p>This private ShopSecure link stays the same while carrier updates are received.</p>
                    <p>Do not forward it to anyone who should not see the delivery status.</p>
                </div>
                """.formatted(safeName, safeRole, safeTrackingUrl);
    }

    public String orderCarrierPickupConfirmedBody(
            String firstName,
            CreatorRole participantRole,
            String itemDescription,
            String trackingReference,
            String trackingUrl,
            Instant pickupConfirmedAt,
            EscrowReleasePolicy releasePolicy,
            SettlementStatus settlementStatus,
            ShipmentStatus shipmentStatus
    ) {
        String safeName = HtmlUtils.htmlEscape(
                firstName == null || firstName.isBlank() ? "there" : firstName
        );
        String safeRole = HtmlUtils.htmlEscape(
                participantRole == CreatorRole.BUYER ? "Buyer" : "Vendor"
        );
        String safeDescription = HtmlUtils.htmlEscape(itemDescription);
        String safeTrackingReference = HtmlUtils.htmlEscape(
                trackingReference == null || trackingReference.isBlank()
                        ? "Pending carrier assignment"
                        : trackingReference
        );
        String safeTrackingUrl = HtmlUtils.htmlEscape(trackingUrl);
        String safeEventTime = HtmlUtils.htmlEscape(
                WAYBILL_EVENT_TIME.format(LocalDateTime.ofInstant(pickupConfirmedAt, ZoneOffset.UTC))
        );
        String escrowMessage = escrowMessage(releasePolicy, settlementStatus);
        String currentStage = shipmentStatus == null
                ? "Pickup confirmed"
                : switch (shipmentStatus) {
                    case IN_TRANSIT -> "In transit";
                    case DELIVERED -> "Delivered";
                    case FAILED, CANCELLED -> "Carrier exception after pickup";
                    default -> "Pickup confirmed";
                };
        return """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #16a34a;">Carrier pickup confirmed</h2>
                    <p>Hi <strong>%s</strong>,</p>
                    <p>The carrier confirmed that the package below was picked up.</p>
                    <div style="background: #f8fafc; border-radius: 8px; padding: 16px; margin: 16px 0;">
                        <p><strong>Item:</strong> %s</p>
                        <p><strong>Your role:</strong> %s</p>
                        <p><strong>Current stage:</strong> %s</p>
                        <p><strong>Pickup confirmed at:</strong> %s</p>
                        <p><strong>Tracking reference:</strong> %s</p>
                    </div>
                    <p>%s</p>
                    <p>
                        <a href="%s" target="_blank" rel="noopener noreferrer"
                           style="display: inline-block; background: #16a34a; color: #ffffff; padding: 12px 18px; border-radius: 6px; text-decoration: none;">
                            Track order
                        </a>
                    </p>
                    <p>The tracking page remains available while the carrier completes delivery.</p>
                </div>
                """.formatted(
                safeName,
                safeDescription,
                safeRole,
                HtmlUtils.htmlEscape(currentStage),
                safeEventTime,
                safeTrackingReference,
                HtmlUtils.htmlEscape(escrowMessage),
                safeTrackingUrl
        );
    }

    private String escrowMessage(
            EscrowReleasePolicy releasePolicy,
            SettlementStatus settlementStatus
    ) {
        if (releasePolicy != EscrowReleasePolicy.CARRIER_PICKUP) {
            return "The item funds remain in escrow until the buyer confirms delivery.";
        }
        if (settlementStatus == null) {
            return "Carrier pickup is recorded; vendor payout status is not yet available.";
        }
        return switch (settlementStatus) {
            case COMPLETED -> "ShopSecure confirmed the item-fund payout to the vendor's verified bank account.";
            case PENDING, DISPATCH_UNKNOWN ->
                    "ShopSecure queued the item-fund payout and is verifying the provider outcome.";
            case REQUIRES_ACTION, FAILED, REVERSED ->
                    "The vendor payout requires review; do not assume the item funds were released.";
            case NOT_STARTED -> "Carrier pickup is recorded; vendor payout has not started.";
        };
    }

    public String waybillAwaitingPickupBody(
            String firstName,
            WaybillPartyRole partyRole,
            String description,
            String trackingReference,
            String trackingUrl,
            LocalDateTime eventTime
    ) {
        String safeName = HtmlUtils.htmlEscape(
                firstName == null || firstName.isBlank() ? "there" : firstName
        );
        String safeDescription = HtmlUtils.htmlEscape(description);
        String safeRole = HtmlUtils.htmlEscape(
                partyRole == WaybillPartyRole.SENDER ? "Sender" : "Recipient"
        );
        String safeTrackingReference = HtmlUtils.htmlEscape(
                trackingReference == null || trackingReference.isBlank()
                        ? "Pending carrier assignment"
                        : trackingReference
        );
        String safeTrackingUrl = HtmlUtils.htmlEscape(trackingUrl);
        String safeEventTime = HtmlUtils.htmlEscape(WAYBILL_EVENT_TIME.format(eventTime));
        return """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #16a34a;">Payment confirmed - package entered pickup stage</h2>
                    <p>Hi <strong>%s</strong>,</p>
                    <p>Payment was confirmed and this package entered the carrier pickup stage.</p>
                    <div style="background: #f8fafc; border-radius: 8px; padding: 16px; margin: 16px 0;">
                        <p><strong>Package:</strong> %s</p>
                        <p><strong>Your role:</strong> %s</p>
                        <p><strong>Stage recorded:</strong> Awaiting carrier pickup</p>
                        <p><strong>Recorded at:</strong> %s</p>
                        <p><strong>Tracking reference:</strong> %s</p>
                    </div>
                    <p>
                        <a href="%s" target="_blank" rel="noopener noreferrer"
                           style="display: inline-block; background: #16a34a; color: #ffffff; padding: 12px 18px; border-radius: 6px; text-decoration: none;">
                            Track package
                        </a>
                    </p>
                    <p>Use the tracking link for the current carrier status, which may have advanced since this notification was queued.</p>
                </div>
                """.formatted(
                safeName,
                safeDescription,
                safeRole,
                safeEventTime,
                safeTrackingReference,
                safeTrackingUrl
        );
    }

    public String counterpartyVerifiedBody(
            String creatorFirstName,
            String itemDescription,
            CreatorRole counterpartyRole
    ) {
        String safeName = HtmlUtils.htmlEscape(
                creatorFirstName == null || creatorFirstName.isBlank() ? "there" : creatorFirstName
        );
        String safeDescription = HtmlUtils.htmlEscape(itemDescription);
        String party = counterpartyRole == CreatorRole.VENDOR ? "seller" : "buyer";
        String nextStep = counterpartyRole == CreatorRole.VENDOR
                ? "You can now request shipping quotes and select a delivery option."
                : "The buyer can now select a delivery option and view payment instructions.";
        return """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #16a34a;">%s accepted the order</h2>
                    <p>Hi <strong>%s</strong>,</p>
                    <p>The %s has verified their phone number and accepted your order.</p>
                    <div style="background: #f8fafc; border-radius: 8px; padding: 16px; margin: 16px 0;">
                        <p><strong>Item:</strong> %s</p>
                        <p><strong>Status:</strong> Parties verified</p>
                    </div>
                    <p>%s</p>
                </div>
                """.formatted(
                HtmlUtils.htmlEscape(party.substring(0, 1).toUpperCase() + party.substring(1)),
                safeName,
                HtmlUtils.htmlEscape(party),
                safeDescription,
                HtmlUtils.htmlEscape(nextStep)
        );
    }

    public String vendorSettlementCompletedBody(
            String vendorFirstName,
            String itemDescription,
            BigDecimal amount,
            String currency,
            SettlementType settlementType,
            SettlementTrigger trigger
    ) {
        String safeName = HtmlUtils.htmlEscape(
                vendorFirstName == null || vendorFirstName.isBlank() ? "there" : vendorFirstName
        );
        String safeDescription = HtmlUtils.htmlEscape(itemDescription);
        String safeAmount = HtmlUtils.htmlEscape(
                (currency == null || currency.isBlank() ? "NGN" : currency.toUpperCase(Locale.ROOT))
                        + " " + String.format(Locale.ROOT, "%,.2f", amount)
        );
        boolean externalPayout = settlementType == SettlementType.EXTERNAL_PAYOUT;
        boolean carrierPickup = trigger == SettlementTrigger.CARRIER_PICKUP;
        String heading = carrierPickup
                ? "Carrier pickup confirmed - bank payout completed"
                : externalPayout
                        ? "Buyer confirmed delivery - account credited"
                        : "Buyer confirmed delivery - wallet credited";
        String introduction = carrierPickup
                ? "The carrier confirmed pickup and the payout for the item below is complete."
                : "The buyer has confirmed delivery of the item below.";
        String destination = externalPayout
                ? "Credited to your verified bank account"
                : "Credited to your ShopSecure wallet";
        String note = externalPayout
                ? "Paystack confirmed that your payout was completed."
                : "The funds are now available in your ShopSecure wallet.";
        return """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #16a34a;">%s</h2>
                    <p>Hi <strong>%s</strong>,</p>
                    <p>%s</p>
                    <div style="background: #f8fafc; border-radius: 8px; padding: 16px; margin: 16px 0;">
                        <p><strong>Item:</strong> %s</p>
                        <p><strong>Amount:</strong> %s</p>
                        <p><strong>Funds:</strong> %s</p>
                    </div>
                    <p>%s</p>
                </div>
                """.formatted(
                HtmlUtils.htmlEscape(heading),
                safeName,
                HtmlUtils.htmlEscape(introduction),
                safeDescription,
                safeAmount,
                HtmlUtils.htmlEscape(destination),
                HtmlUtils.htmlEscape(note)
        );
    }

    public String buyerSettlementCompletedBody(
            String buyerFirstName,
            String itemDescription,
            BigDecimal amount,
            String currency,
            SettlementTrigger trigger
    ) {
        String safeName = HtmlUtils.htmlEscape(
                buyerFirstName == null || buyerFirstName.isBlank() ? "there" : buyerFirstName
        );
        String safeDescription = HtmlUtils.htmlEscape(itemDescription);
        String safeAmount = HtmlUtils.htmlEscape(
                (currency == null || currency.isBlank() ? "NGN" : currency.toUpperCase(Locale.ROOT))
                        + " " + String.format(Locale.ROOT, "%,.2f", amount)
        );
        boolean carrierPickup = trigger == SettlementTrigger.CARRIER_PICKUP;
        String heading = carrierPickup
                ? "Package picked up - vendor payment released"
                : "Thank you for confirming delivery";
        String introduction = carrierPickup
                ? "The carrier confirmed pickup of the item below and the vendor payout is complete."
                : "Thank you for confirming delivery of the item below.";
        String completion = carrierPickup
                ? "The package remains in the carrier delivery flow. Use your tracking link for its current status."
                : "The vendor has been credited and your order is now complete.";
        return """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #16a34a;">%s</h2>
                    <p>Hi <strong>%s</strong>,</p>
                    <p>%s</p>
                    <div style="background: #f8fafc; border-radius: 8px; padding: 16px; margin: 16px 0;">
                        <p><strong>Item:</strong> %s</p>
                        <p><strong>Amount released:</strong> %s</p>
                        <p><strong>Vendor settlement:</strong> Completed</p>
                    </div>
                    <p>%s</p>
                </div>
                """.formatted(
                HtmlUtils.htmlEscape(heading),
                safeName,
                HtmlUtils.htmlEscape(introduction),
                safeDescription,
                safeAmount,
                HtmlUtils.htmlEscape(completion)
        );
    }

    public String orderDisputeOpenedBody(
            String firstName,
            OrderDisputeNotificationAudience audience,
            Long orderId,
            String itemDescription,
            String reason
    ) {
        boolean operations = audience == OrderDisputeNotificationAudience.OPERATIONS;
        String safeName = HtmlUtils.htmlEscape(
                firstName == null || firstName.isBlank() ? "there" : firstName
        );
        String safeHeading = HtmlUtils.htmlEscape(
                operations ? "New order dispute received" : "Order dispute received"
        );
        String safeIntroduction = HtmlUtils.htmlEscape(operations
                ? "A dispute was submitted for the order below."
                : "A dispute has been submitted for this order and recorded by ShopSecure.");
        String safeOrderId = HtmlUtils.htmlEscape(String.valueOf(orderId));
        String safeDescription = HtmlUtils.htmlEscape(itemDescription);
        String safeReason = HtmlUtils.htmlEscape(reason);
        return """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #16a34a;">%s</h2>
                    <p>Hi <strong>%s</strong>,</p>
                    <p>%s</p>
                    <div style="background: #f8fafc; border-radius: 8px; padding: 16px; margin: 16px 0;">
                        <p><strong>Order:</strong> #%s</p>
                        <p><strong>Item:</strong> %s</p>
                        <p><strong>Reason:</strong></p>
                        <div style="white-space: pre-wrap; overflow-wrap: anywhere;">%s</div>
                    </div>
                    <p>The ShopSecure team will review the dispute and contact both parties.</p>
                    <p>Submitting a dispute does not automatically cancel delivery, reverse settlement, or issue a refund.</p>
                </div>
                """.formatted(
                safeHeading,
                safeName,
                safeIntroduction,
                safeOrderId,
                safeDescription,
                safeReason
        );
    }

    public String vendorPaymentConfirmedBody(
            String vendorFirstName,
            String itemDescription,
            BigDecimal amount,
            String currency
    ) {
        return paymentConfirmedBody(
                vendorFirstName,
                "Buyer payment confirmed",
                "The buyer has paid for the order below.",
                itemDescription,
                amount,
                currency,
                "Please prepare the item for pickup. Our delivery partner will contact you with pickup details."
        );
    }

    public String buyerPaymentConfirmedBody(
            String buyerFirstName,
            String itemDescription,
            BigDecimal amount,
            String currency
    ) {
        return paymentConfirmedBody(
                buyerFirstName,
                "Order created and payment confirmed",
                "Your order has been created and your payment has been confirmed.",
                itemDescription,
                amount,
                currency,
                "The vendor has been notified and pickup is being arranged. We will update you as delivery progresses."
        );
    }

    private String paymentConfirmedBody(
            String firstName,
            String heading,
            String introduction,
            String itemDescription,
            BigDecimal amount,
            String currency,
            String nextStep
    ) {
        String safeName = HtmlUtils.htmlEscape(
                firstName == null || firstName.isBlank() ? "there" : firstName
        );
        String safeAmount = HtmlUtils.htmlEscape(
                (currency == null || currency.isBlank() ? "NGN" : currency.toUpperCase(Locale.ROOT))
                        + " " + String.format(Locale.ROOT, "%,.2f", amount)
        );
        return """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #16a34a;">%s</h2>
                    <p>Hi <strong>%s</strong>,</p>
                    <p>%s</p>
                    <div style="background: #f8fafc; border-radius: 8px; padding: 16px; margin: 16px 0;">
                        <p><strong>Item:</strong> %s</p>
                        <p><strong>Amount paid:</strong> %s</p>
                    </div>
                    <p>%s</p>
                </div>
                """.formatted(
                HtmlUtils.htmlEscape(heading),
                safeName,
                HtmlUtils.htmlEscape(introduction),
                HtmlUtils.htmlEscape(itemDescription),
                safeAmount,
                HtmlUtils.htmlEscape(nextStep)
        );
    }
}
