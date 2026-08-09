package com.example.shopsecure.service;

import com.example.shopsecure.entity.CreatorRole;
import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.OrderCounterparty;
import com.example.shopsecure.entity.PaymentStatus;
import com.example.shopsecure.entity.SettlementStatus;
import com.example.shopsecure.entity.SettlementType;
import com.example.shopsecure.event.CounterpartyVerifiedEvent;
import com.example.shopsecure.event.OrderFundedEvent;
import com.example.shopsecure.event.VendorSettlementCompletedEvent;
import com.example.shopsecure.provider.notification.SmsProvider;
import com.example.shopsecure.repository.OrderCounterpartyRepository;
import com.example.shopsecure.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderNotificationService {

    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;
    private final SmsProvider smsProvider;
    private final OrderRepository orderRepository;
    private final OrderCounterpartyRepository counterpartyRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendCounterpartyVerifiedEmail(CounterpartyVerifiedEvent event) {
        try {
            String counterparty = event.counterpartyRole() == CreatorRole.VENDOR ? "Seller" : "Buyer";
            emailService.sendEmail(
                    event.creatorEmail(),
                    counterparty + " accepted the order",
                    emailTemplateService.counterpartyVerifiedBody(
                            event.creatorFirstName(),
                            event.itemDescription(),
                            event.counterpartyRole()
                    )
            );
        } catch (RuntimeException exception) {
            log.error("Unable to send counterparty verification email", exception);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void sendOrderFundedNotifications(OrderFundedEvent event) {
        Order order = orderRepository.findById(event.orderId()).orElse(null);
        if (order == null || order.getPaymentStatus() != PaymentStatus.CONFIRMED) {
            return;
        }
        OrderCounterparty counterparty = counterpartyRepository.findByOrder_Id(order.getId()).orElse(null);
        if (counterparty == null) {
            log.warn("Unable to send funded-order notifications: orderId={}, reason=counterparty missing",
                    event.orderId());
            return;
        }

        Participant buyer = order.getCreatorRole() == CreatorRole.BUYER
                ? new Participant(order.getCreatorFirstName(), order.getCreatorEmail(), order.getCreatorPhoneNumber())
                : new Participant(counterparty.getFirstName(), counterparty.getEmail(), counterparty.getPhoneNumber());
        Participant vendor = order.getCreatorRole() == CreatorRole.VENDOR
                ? new Participant(order.getCreatorFirstName(), order.getCreatorEmail(), order.getCreatorPhoneNumber())
                : new Participant(counterparty.getFirstName(), counterparty.getEmail(), counterparty.getPhoneNumber());
        var amountPaid = order.getTotalAmount() == null ? order.getItemAmount() : order.getTotalAmount();

        sendEmail(
                vendor.email(),
                "Buyer payment confirmed - prepare for pickup",
                emailTemplateService.vendorPaymentConfirmedBody(
                        vendor.firstName(),
                        order.getItemDescription(),
                        amountPaid,
                        order.getCurrency()
                ),
                event.orderId(),
                "vendor"
        );
        sendSms(
                vendor.phoneNumber(),
                "ShopSecure: Buyer payment is confirmed. Please prepare the item for pickup. "
                        + "Our delivery partner will contact you with pickup details.",
                event.orderId(),
                "vendor"
        );
        sendEmail(
                buyer.email(),
                "Order created and payment confirmed - pickup is being arranged",
                emailTemplateService.buyerPaymentConfirmedBody(
                        buyer.firstName(),
                        order.getItemDescription(),
                        amountPaid,
                        order.getCurrency()
                ),
                event.orderId(),
                "buyer"
        );
        sendSms(
                buyer.phoneNumber(),
                "ShopSecure: Your order has been created and payment is confirmed. "
                        + "The vendor has been notified and pickup is being arranged. "
                        + "We will update you as delivery progresses.",
                event.orderId(),
                "buyer"
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendSettlementCompletedNotifications(VendorSettlementCompletedEvent event) {
        Order order = orderRepository.findById(event.orderId()).orElse(null);
        if (order == null || order.getSettlementStatus() != SettlementStatus.COMPLETED) {
            return;
        }
        OrderCounterparty counterparty = counterpartyRepository.findByOrder_Id(order.getId()).orElse(null);
        if (counterparty == null) {
            log.warn("Unable to send settlement notifications: orderId={}, reason=counterparty missing",
                    event.orderId());
            return;
        }

        Participant buyer = order.getCreatorRole() == CreatorRole.BUYER
                ? new Participant(order.getCreatorFirstName(), order.getCreatorEmail(), order.getCreatorPhoneNumber())
                : new Participant(counterparty.getFirstName(), counterparty.getEmail(), counterparty.getPhoneNumber());
        Participant vendor = order.getCreatorRole() == CreatorRole.VENDOR
                ? new Participant(order.getCreatorFirstName(), order.getCreatorEmail(), order.getCreatorPhoneNumber())
                : new Participant(counterparty.getFirstName(), counterparty.getEmail(), counterparty.getPhoneNumber());
        boolean externalPayout = event.settlementType() == SettlementType.EXTERNAL_PAYOUT;
        String formattedAmount = formatAmount(order.getItemAmount(), order.getCurrency());

        sendEmail(
                vendor.email(),
                externalPayout
                        ? "Buyer confirmed delivery - account credited"
                        : "Buyer confirmed delivery - wallet credited",
                emailTemplateService.vendorSettlementCompletedBody(
                        vendor.firstName(),
                        order.getItemDescription(),
                        order.getItemAmount(),
                        order.getCurrency(),
                        event.settlementType()
                ),
                event.orderId(),
                "vendor"
        );
        sendSms(
                vendor.phoneNumber(),
                "ShopSecure: The buyer has confirmed delivery. Your "
                        + (externalPayout ? "account" : "ShopSecure wallet")
                        + " has been credited with " + formattedAmount + ".",
                event.orderId(),
                "vendor"
        );
        sendEmail(
                buyer.email(),
                "Thank you for confirming delivery - vendor credited",
                emailTemplateService.buyerSettlementCompletedBody(
                        buyer.firstName(),
                        order.getItemDescription(),
                        order.getItemAmount(),
                        order.getCurrency()
                ),
                event.orderId(),
                "buyer"
        );
        sendSms(
                buyer.phoneNumber(),
                "ShopSecure: Thank you for confirming delivery. The vendor has been credited with "
                        + formattedAmount + ".",
                event.orderId(),
                "buyer"
        );
    }

    private void sendEmail(
            String email,
            String subject,
            String body,
            Long orderId,
            String recipientRole
    ) {
        try {
            emailService.sendEmail(email, subject, body);
        } catch (RuntimeException exception) {
            log.error("Unable to send order email: orderId={}, recipientRole={}",
                    orderId, recipientRole, exception);
        }
    }

    private void sendSms(String phoneNumber, String message, Long orderId, String recipientRole) {
        try {
            smsProvider.sendMessage(phoneNumber, message);
        } catch (RuntimeException exception) {
            log.error("Unable to send order SMS: orderId={}, recipientRole={}",
                    orderId, recipientRole, exception);
        }
    }

    private String formatAmount(BigDecimal amount, String currency) {
        String currencyCode = currency == null || currency.isBlank()
                ? "NGN"
                : currency.toUpperCase(Locale.ROOT);
        return currencyCode + " " + String.format(Locale.ROOT, "%,.2f", amount);
    }

    private record Participant(String firstName, String email, String phoneNumber) {
    }
}
