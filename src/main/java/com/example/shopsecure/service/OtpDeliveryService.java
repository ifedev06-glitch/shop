package com.example.shopsecure.service;

import com.example.shopsecure.entity.OtpPurpose;
import com.example.shopsecure.provider.notification.SmsProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

@Service
@RequiredArgsConstructor
public class OtpDeliveryService {

    private final SmsProvider smsProvider;
    private final EmailService emailService;

    public void send(String phoneNumber, String email, String code, OtpPurpose purpose) {
        String action = action(purpose);
        smsProvider.sendMessage(
                phoneNumber,
                "Your ShopSecure code is " + code + ". Use it to " + action + ". It expires in 5 minutes."
        );
        sendEmail(email, code, purpose);
    }

    public void sendEmail(String email, String code, OtpPurpose purpose) {
        String action = action(purpose);
        String subject = switch (purpose) {
            case EMAIL_VERIFICATION -> "Verify your ShopSecure email";
            case PASSWORD_RESET -> "Reset your ShopSecure password";
            default -> "Your ShopSecure verification code";
        };
        emailService.sendEmail(
                email,
                subject,
                """
                        <div style="font-family: Arial, sans-serif; max-width: 560px; margin: 0 auto;">
                            <h2>ShopSecure verification</h2>
                            <p>Use this code to %s:</p>
                            <p style="font-size: 28px; font-weight: bold; letter-spacing: 6px;">%s</p>
                            <p>This code expires in 5 minutes. Do not share it.</p>
                        </div>
                        """.formatted(HtmlUtils.htmlEscape(action), HtmlUtils.htmlEscape(code))
        );
    }

    private String action(OtpPurpose purpose) {
        return switch (purpose) {
            case DELIVERY_CONFIRMATION -> "confirm delivery";
            case ORDER_ACCESS_RECOVERY -> "restore access to your order";
            case ORDER_COUNTERPARTY -> "verify your order details";
            case EMAIL_VERIFICATION -> "verify your email address";
            case PASSWORD_RESET -> "reset your password";
        };
    }
}
