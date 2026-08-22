package com.example.shopsecure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;

@Component
@Profile("prod")
public class ProductionConfigurationValidator {

    public ProductionConfigurationValidator(
            @Value("${shipping.terminal-africa.enabled}") boolean terminalEnabled,
            @Value("${shipping.terminal-africa.base-url}") String terminalBaseUrl,
            @Value("${shipping.terminal-africa.secret-key}") String terminalSecretKey,
            @Value("${shipping.chowdeck.hard-disabled:true}") boolean chowdeckHardDisabled,
            @Value("${shipping.chowdeck.base-url}") String chowdeckBaseUrl,
            @Value("${shipping.chowdeck.secret-key:}") String chowdeckSecretKey,
            @Value("${shipping.chowdeck.quote-ttl:PT5M}") Duration chowdeckQuoteTtl,
            @Value("${sms.twilio.enabled}") boolean twilioEnabled,
            @Value("${sms.twilio.account-sid}") String twilioAccountSid,
            @Value("${sms.twilio.auth-token}") String twilioAuthToken,
            @Value("${sms.twilio.from-number}") String twilioFromNumber,
            @Value("${sms.twilio.base-url}") String twilioBaseUrl,
            @Value("${paystack.secret-key}") String paystackSecretKey,
            @Value("${paystack.base-url}") String paystackBaseUrl,
            @Value("${resend.api.key}") String resendApiKey,
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${jwt.expiration}") long jwtExpiration,
            @Value("${app.base-url}") String appBaseUrl,
            @Value("${app.frontend-base-url}") String frontendBaseUrl,
            @Value("${order.guest-token-ttl}") Duration guestTokenTtl,
            @Value("${order.tracking-token-ttl}") Duration trackingTokenTtl,
            @Value("${shipping.quote-ttl}") Duration quoteTtl,
            @Value("${payout.reconciliation.batch-size:50}") int payoutBatchSize,
            @Value("${shipping.booking-reconciliation.max-attempts:5}") int shippingMaxAttempts,
            @Value("${shipping.booking-reconciliation.batch-size:25}") int shippingBatchSize,
            @Value("${shipping.booking-reconciliation.retry-delay:PT5M}") Duration shippingRetryDelay,
            @Value("${waybill.invitation-ttl:PT168H}") Duration waybillInvitationTtl,
            @Value("${waybill.otp.max-sends:5}") int waybillOtpMaxSends,
            @Value("${waybill.otp.max-failed-attempts:10}") int waybillOtpMaxFailures,
            @Value("${waybill.booking-reconciliation.max-attempts:5}") int waybillBookingMaxAttempts,
            @Value("${waybill.booking-reconciliation.batch-size:25}") int waybillBookingBatchSize,
            @Value("${waybill.booking-reconciliation.retry-delay:PT5M}") Duration waybillBookingRetryDelay,
            @Value("${waybill.cancellation-reconciliation.max-attempts:5}") int waybillCancellationMaxAttempts,
            @Value("${waybill.cancellation-reconciliation.batch-size:25}") int waybillCancellationBatchSize,
            @Value("${waybill.cancellation-reconciliation.retry-delay:PT5M}") Duration waybillCancellationRetryDelay,
            @Value("${waybill.payment-reconciliation.batch-size:50}") int waybillPaymentBatchSize,
            @Value("${waybill.payment-reconciliation.retry-delay:PT5M}") Duration waybillPaymentRetryDelay,
            @Value("${waybill.refund-reconciliation.batch-size:25}") int waybillRefundBatchSize,
            @Value("${waybill.refund-reconciliation.retry-delay:PT5M}") Duration waybillRefundRetryDelay,
            @Value("${waybill.refund-reconciliation.unknown-timeout:PT1H}") Duration waybillRefundUnknownTimeout,
            @Value("${spring.task.scheduling.pool.size:4}") int schedulerPoolSize
    ) {
        if (!terminalEnabled) {
            throw new IllegalStateException("Terminal Africa must be enabled in production");
        }
        if (!twilioEnabled) {
            throw new IllegalStateException("Twilio SMS must be enabled in production");
        }
        requireText(terminalSecretKey, "Terminal Africa secret key is required in production");
        requireHttps(terminalBaseUrl, "Terminal Africa base URL must use HTTPS in production");
        requireChowdeckBaseUrl(chowdeckBaseUrl);
        requirePositive(chowdeckQuoteTtl, "Chowdeck quote TTL must be positive");
        if (!chowdeckHardDisabled) {
            requireText(chowdeckSecretKey, "Chowdeck secret key is required when Chowdeck is enabled");
            if ("replace-me".equalsIgnoreCase(chowdeckSecretKey.trim())) {
                throw new IllegalStateException("A real Chowdeck secret key is required when enabled");
            }
        }
        requireText(twilioAccountSid, "Twilio account SID is required in production");
        requireText(twilioAuthToken, "Twilio auth token is required in production");
        requireText(twilioFromNumber, "Twilio sender number is required in production");
        requireHttps(twilioBaseUrl, "Twilio base URL must use HTTPS in production");
        requireText(paystackSecretKey, "Paystack secret key is required in production");
        if (!paystackSecretKey.startsWith("sk_live_")) {
            throw new IllegalStateException("Paystack live secret key is required in production");
        }
        requireHttps(paystackBaseUrl, "Paystack base URL must use HTTPS in production");
        requireText(resendApiKey, "Resend API key is required in production");
        requireHttps(appBaseUrl, "Application base URL must use HTTPS in production");
        requireHttps(frontendBaseUrl, "Frontend base URL must use HTTPS in production");
        validateJwtSecret(jwtSecret);
        if (jwtExpiration <= 0) {
            throw new IllegalStateException("JWT expiration must be positive");
        }
        requirePositive(guestTokenTtl, "Guest token TTL must be positive");
        requirePositive(trackingTokenTtl, "Order tracking token TTL must be positive");
        requirePositive(quoteTtl, "Shipping quote TTL must be positive");
        if (payoutBatchSize < 1 || shippingBatchSize < 1) {
            throw new IllegalStateException("Reconciliation batch sizes must be positive");
        }
        if (shippingMaxAttempts < 1) {
            throw new IllegalStateException("Shipping max attempts must be at least one");
        }
        requirePositive(shippingRetryDelay, "Shipping booking retry delay must be positive");
        requirePositive(waybillInvitationTtl, "Waybill invitation TTL must be positive");
        if (waybillOtpMaxSends < 1 || waybillOtpMaxFailures < 1) {
            throw new IllegalStateException("Waybill OTP limits must be positive");
        }
        if (waybillBookingMaxAttempts < 1) {
            throw new IllegalStateException("Waybill booking max attempts must be at least one");
        }
        if (waybillCancellationMaxAttempts < 1) {
            throw new IllegalStateException("Waybill cancellation max attempts must be at least one");
        }
        if (waybillBookingBatchSize < 1
                || waybillCancellationBatchSize < 1
                || waybillPaymentBatchSize < 1
                || waybillRefundBatchSize < 1) {
            throw new IllegalStateException("Waybill reconciliation batch sizes must be positive");
        }
        requirePositive(waybillBookingRetryDelay, "Waybill booking retry delay must be positive");
        requirePositive(
                waybillCancellationRetryDelay,
                "Waybill cancellation retry delay must be positive"
        );
        requirePositive(waybillPaymentRetryDelay, "Waybill payment retry delay must be positive");
        requirePositive(waybillRefundRetryDelay, "Waybill refund retry delay must be positive");
        requirePositive(waybillRefundUnknownTimeout, "Waybill refund unknown timeout must be positive");
        if (schedulerPoolSize < 2) {
            throw new IllegalStateException("Scheduler pool size must be at least two");
        }
    }

    private static void validateJwtSecret(String encodedSecret) {
        requireText(encodedSecret, "JWT secret is required in production");
        try {
            if (Base64.getDecoder().decode(encodedSecret).length < 32) {
                throw new IllegalStateException("JWT secret must contain at least 32 bytes");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT secret must be Base64 encoded", exception);
        }
    }

    private static void requireHttps(String value, String message) {
        requireText(value, message);
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalStateException(message);
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(message, exception);
        }
    }

    private static void requireChowdeckBaseUrl(String value) {
        String message = "Chowdeck base URL must be https://api.chowdeck.com in production";
        requireHttps(value, message);
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (!"api.chowdeck.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || (path != null && !path.isBlank() && !"/".equals(path))
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalStateException(message);
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(message, exception);
        }
    }

    private static void requirePositive(Duration value, String message) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(message);
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
    }
}
