package com.example.shopsecure.config;

import com.example.shopsecure.provider.shipping.ChowdeckShippingProvider;
import com.example.shopsecure.provider.shipping.ShippingProvider;
import com.example.shopsecure.provider.shipping.ShippingProviderCode;
import com.example.shopsecure.provider.shipping.ShippingProviderRegistry;
import com.example.shopsecure.provider.shipping.StubShippingProvider;
import com.example.shopsecure.provider.shipping.TerminalAfricaShippingProvider;
import com.example.shopsecure.provider.notification.SmsProvider;
import com.example.shopsecure.provider.notification.StubSmsProvider;
import com.example.shopsecure.provider.notification.TwilioSmsProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ProviderConfig {

    @Bean
    public ShippingProvider shippingProvider(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${shipping.terminal-africa.enabled:false}") boolean terminalEnabled,
            @Value("${shipping.terminal-africa.base-url:https://api.terminal.africa/v1}") String terminalBaseUrl,
            @Value("${shipping.terminal-africa.secret-key:}") String terminalSecretKey,
            @Value("${shipping.stub.webhook-secret:local-shipping-webhook-secret}") String stubWebhookSecret
    ) {
        if (terminalEnabled) {
            if (terminalSecretKey == null || terminalSecretKey.isBlank()) {
                throw new IllegalStateException("Terminal Africa secret key is required when shipping is enabled");
            }
            return new TerminalAfricaShippingProvider(
                    restTemplate,
                    objectMapper,
                    terminalSecretKey,
                    terminalBaseUrl
            );
        }
        return new StubShippingProvider(objectMapper, stubWebhookSecret);
    }

    @Bean
    public ShippingProviderRegistry shippingProviderRegistry(
            ShippingProvider defaultProvider,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${shipping.terminal-africa.enabled:false}") boolean terminalEnabled,
            @Value("${shipping.stub.webhook-secret:local-shipping-webhook-secret}") String stubWebhookSecret,
            @Value("${shipping.chowdeck.secret-key:}") String chowdeckSecretKey,
            @Value("${shipping.chowdeck.base-url:https://api.chowdeck.com}") String chowdeckBaseUrl,
            @Value("${shipping.chowdeck.quote-ttl:PT5M}") java.time.Duration chowdeckQuoteTtl,
            @Value("${shipping.chowdeck.hard-disabled:true}") boolean chowdeckHardDisabled
    ) {
        StubShippingProvider stubProvider = new StubShippingProvider(objectMapper, stubWebhookSecret);
        ChowdeckShippingProvider chowdeckProvider = new ChowdeckShippingProvider(
                restTemplate,
                objectMapper,
                chowdeckSecretKey,
                chowdeckBaseUrl,
                chowdeckQuoteTtl,
                chowdeckHardDisabled
        );
        return new ShippingProviderRegistry(
                defaultProvider,
                terminalEnabled
                        ? ShippingProviderCode.TERMINAL_AFRICA
                        : ShippingProviderCode.STUB,
                chowdeckProvider,
                terminalEnabled ? defaultProvider : null,
                stubProvider
        );
    }

    @Bean
    public SmsProvider smsProvider(
            RestTemplate restTemplate,
            @Value("${sms.twilio.enabled:false}") boolean twilioEnabled,
            @Value("${sms.twilio.account-sid:}") String accountSid,
            @Value("${sms.twilio.auth-token:}") String authToken,
            @Value("${sms.twilio.from-number:}") String fromNumber,
            @Value("${sms.twilio.base-url:https://api.twilio.com}") String baseUrl
    ) {
        if (!twilioEnabled) {
            return new StubSmsProvider();
        }
        return new TwilioSmsProvider(restTemplate, accountSid, authToken, fromNumber, baseUrl);
    }
}
