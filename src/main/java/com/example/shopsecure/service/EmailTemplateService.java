package com.example.shopsecure.service;

import com.example.shopsecure.entity.CreatorRole;
import com.example.shopsecure.entity.SettlementType;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.util.Locale;

@Service
public class EmailTemplateService {

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
            SettlementType settlementType
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
        String heading = externalPayout
                ? "Buyer confirmed delivery - account credited"
                : "Buyer confirmed delivery - wallet credited";
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
                    <p>The buyer has confirmed delivery of the item below.</p>
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
            String currency
    ) {
        String safeName = HtmlUtils.htmlEscape(
                buyerFirstName == null || buyerFirstName.isBlank() ? "there" : buyerFirstName
        );
        String safeDescription = HtmlUtils.htmlEscape(itemDescription);
        String safeAmount = HtmlUtils.htmlEscape(
                (currency == null || currency.isBlank() ? "NGN" : currency.toUpperCase(Locale.ROOT))
                        + " " + String.format(Locale.ROOT, "%,.2f", amount)
        );
        return """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #16a34a;">Thank you for confirming delivery</h2>
                    <p>Hi <strong>%s</strong>,</p>
                    <p>Thank you for confirming delivery of the item below.</p>
                    <div style="background: #f8fafc; border-radius: 8px; padding: 16px; margin: 16px 0;">
                        <p><strong>Item:</strong> %s</p>
                        <p><strong>Amount released:</strong> %s</p>
                        <p><strong>Vendor settlement:</strong> Completed</p>
                    </div>
                    <p>The vendor has been credited and your order is now complete.</p>
                </div>
                """.formatted(safeName, safeDescription, safeAmount);
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
