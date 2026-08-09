package com.example.shopsecure.integration;

import com.example.shopsecure.provider.notification.SmsProvider;
import com.example.shopsecure.provider.payment.PaymentProvider;
import com.example.shopsecure.provider.payment.PayoutProvider;
import com.example.shopsecure.provider.shipping.ShippingProvider;
import com.example.shopsecure.service.EmailService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

@TestConfiguration(proxyBeanMethods = false)
public class ReconciliationSettlementTestConfig {

    @Bean("reconciliationEmailService")
    @Primary
    EmailService reconciliationEmailService() {
        return mock(EmailService.class);
    }

    @Bean("reconciliationSmsProvider")
    @Primary
    SmsProvider reconciliationSmsProvider() {
        return mock(SmsProvider.class);
    }

    @Bean("reconciliationPaymentProvider")
    @Primary
    PaymentProvider reconciliationPaymentProvider() {
        return mock(PaymentProvider.class);
    }

    @Bean("reconciliationPayoutProvider")
    @Primary
    PayoutProvider reconciliationPayoutProvider() {
        return mock(PayoutProvider.class);
    }

    @Bean("reconciliationShippingProvider")
    @Primary
    ShippingProvider reconciliationShippingProvider() {
        return mock(ShippingProvider.class);
    }
}
