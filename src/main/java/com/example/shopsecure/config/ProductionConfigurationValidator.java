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
            @Value("${order.guest-token-ttl}") Duration guestTokenTtl,
            @Value("${shipping.quote-ttl}") Duration quoteTtl,
            @Value("${payout.max-attempts:3}") int payoutMaxAttempts,
            @Value("${payout.reconciliation.batch-size:50}") int payoutBatchSize,
            @Value("${shipping.booking-reconciliation.max-attempts:5}") int shippingMaxAttempts,
            @Value("${shipping.booking-reconciliation.batch-size:25}") int shippingBatchSize,
            @Value("${shipping.booking-reconciliation.retry-delay:PT5M}") Duration shippingRetryDelay,
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
        validateJwtSecret(jwtSecret);
        if (jwtExpiration <= 0) {
            throw new IllegalStateException("JWT expiration must be positive");
        }
        requirePositive(guestTokenTtl, "Guest token TTL must be positive");
        requirePositive(quoteTtl, "Shipping quote TTL must be positive");
        if (payoutMaxAttempts < 1) {
            throw new IllegalStateException("Payout max attempts must be at least one");
        }
        if (payoutBatchSize < 1 || shippingBatchSize < 1) {
            throw new IllegalStateException("Reconciliation batch sizes must be positive");
        }
        if (shippingMaxAttempts < 1) {
            throw new IllegalStateException("Shipping max attempts must be at least one");
        }
        requirePositive(shippingRetryDelay, "Shipping booking retry delay must be positive");
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
