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

    private ProductionConfigurationValidator validator(String paystackSecretKey) {
        String jwtSecret = Base64.getEncoder().encodeToString(new byte[32]);
        return new ProductionConfigurationValidator(
                true,
                "https://api.terminal.africa/v1",
                "terminal-secret",
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
                Duration.ofDays(7),
                Duration.ofMinutes(15),
                3,
                50,
                5,
                25,
                Duration.ofMinutes(5),
                4
        );
    }
}
