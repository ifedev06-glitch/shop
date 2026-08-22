package com.example.shopsecure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationValidatorTest {

    @Test
    void requiresLivePaystackSecret() {
        assertThatThrownBy(() -> validator("sk_test_example"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Paystack live secret key is required in production");
        assertThatCode(() -> validator("sk_live_example")).doesNotThrowAnyException();
    }

    @Test
    void requiresPositiveWaybillCancellationBatchSize() {
        assertThatThrownBy(() -> validator(
                "sk_live_example",
                5,
                0,
                Duration.ofMinutes(5)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Waybill reconciliation batch sizes must be positive");
    }

    @Test
    void requiresPositiveWaybillCancellationMaxAttempts() {
        assertThatThrownBy(() -> validator(
                "sk_live_example",
                0,
                25,
                Duration.ofMinutes(5)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Waybill cancellation max attempts must be at least one");
    }

    @Test
    void requiresPositiveWaybillCancellationRetryDelay() {
        assertThatThrownBy(() -> validator(
                "sk_live_example",
                5,
                25,
                Duration.ZERO
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Waybill cancellation retry delay must be positive");
    }

    @Test
    void requiresPositiveOrderTrackingTokenTtl() {
        assertThatThrownBy(() -> validator("sk_live_example", Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Order tracking token TTL must be positive");
    }

    @Test
    void acceptsHardDisabledChowdeckConfigurationWithoutSecret() {
        assertThatCode(() -> validator("sk_live_example")).doesNotThrowAnyException();
    }

    @Test
    void enabledChowdeckRequiresRealSecretAndCanonicalHost() {
        assertThatThrownBy(() -> validatorWithChowdeck(
                false,
                "https://api.chowdeck.com",
                "replace-me"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A real Chowdeck secret key is required when enabled");
        assertThatThrownBy(() -> validatorWithChowdeck(
                false,
                "https://user@api.chowdeck.com:8443",
                "sk_live_example"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Chowdeck base URL must be https://api.chowdeck.com in production");
        assertThatCode(() -> validatorWithChowdeck(
                false,
                "https://api.chowdeck.com",
                "sk_live_example"
        )).doesNotThrowAnyException();
    }

    private ProductionConfigurationValidator validator(String paystackSecretKey) {
        return validator(
                paystackSecretKey,
                Duration.ofDays(180),
                5,
                25,
                Duration.ofMinutes(5)
        );
    }

    private ProductionConfigurationValidator validator(
            String paystackSecretKey,
            Duration trackingTokenTtl
    ) {
        return validator(
                paystackSecretKey,
                trackingTokenTtl,
                5,
                25,
                Duration.ofMinutes(5)
        );
    }

    private ProductionConfigurationValidator validator(
            String paystackSecretKey,
            int cancellationMaxAttempts,
            int cancellationBatchSize,
            Duration cancellationRetryDelay
    ) {
        return validator(
                paystackSecretKey,
                Duration.ofDays(180),
                cancellationMaxAttempts,
                cancellationBatchSize,
                cancellationRetryDelay
        );
    }

    private ProductionConfigurationValidator validator(
            String paystackSecretKey,
            Duration trackingTokenTtl,
            int cancellationMaxAttempts,
            int cancellationBatchSize,
            Duration cancellationRetryDelay
    ) {
        return validator(
                paystackSecretKey,
                trackingTokenTtl,
                cancellationMaxAttempts,
                cancellationBatchSize,
                cancellationRetryDelay,
                true,
                "https://api.chowdeck.com",
                ""
        );
    }

    private ProductionConfigurationValidator validatorWithChowdeck(
            boolean hardDisabled,
            String baseUrl,
            String secretKey
    ) {
        return validator(
                "sk_live_example",
                Duration.ofDays(180),
                5,
                25,
                Duration.ofMinutes(5),
                hardDisabled,
                baseUrl,
                secretKey
        );
    }

    private ProductionConfigurationValidator validator(
            String paystackSecretKey,
            Duration trackingTokenTtl,
            int cancellationMaxAttempts,
            int cancellationBatchSize,
            Duration cancellationRetryDelay,
            boolean chowdeckHardDisabled,
            String chowdeckBaseUrl,
            String chowdeckSecretKey
    ) {
        String jwtSecret = Base64.getEncoder().encodeToString(new byte[32]);
        return new ProductionConfigurationValidator(
                true,
                "https://api.terminal.africa/v1",
                "terminal-secret",
                chowdeckHardDisabled,
                chowdeckBaseUrl,
                chowdeckSecretKey,
                Duration.ofMinutes(5),
                true,
                "AC123",
                "twilio-secret",
                "+2348000000000",
                "https://api.twilio.com",
                paystackSecretKey,
                "https://api.paystack.co",
                "resend-secret",
                jwtSecret,
                3_600_000,
                "https://shopsecure.app",
                "https://www.shopsecure.app",
                Duration.ofDays(7),
                trackingTokenTtl,
                Duration.ofMinutes(15),
                50,
                5,
                25,
                Duration.ofMinutes(5),
                Duration.ofDays(7),
                5,
                10,
                5,
                25,
                Duration.ofMinutes(5),
                cancellationMaxAttempts,
                cancellationBatchSize,
                cancellationRetryDelay,
                50,
                Duration.ofMinutes(5),
                25,
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                4
        );
    }
}
