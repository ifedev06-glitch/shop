package com.example.shopsecure.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PaystackWebhookRequest {
    private String event;
    private PaystackWebhookData data;

    @Data
    public static class PaystackWebhookData {
        private String reference;
        private String status;
        private BigDecimal amount; // Paystack sends in kobo
        private String channel;

        @JsonProperty("paid_at")
        private String paidAt;

        @JsonProperty("transfer_code")
        private String transferCode;

        @JsonProperty("reason")
        private String reason;
    }
}
