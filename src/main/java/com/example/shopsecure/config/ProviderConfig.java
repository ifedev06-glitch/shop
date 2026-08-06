package com.example.shopsecure.config;

import com.example.shopsecure.provider.shipping.ShippingProvider;
import com.example.shopsecure.provider.shipping.StubShippingProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class ProviderConfig {

    @Bean
    public ShippingProvider shippingProvider(
            ObjectMapper objectMapper,
            @Value("${shipping.webhook-secret}") String webhookSecret
    ) {
        return new StubShippingProvider(objectMapper, webhookSecret);
    }
}
