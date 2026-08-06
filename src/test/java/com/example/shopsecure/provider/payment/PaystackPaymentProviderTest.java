package com.example.shopsecure.provider.payment;

import com.example.shopsecure.provider.common.WebhookPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PaystackPaymentProviderTest {

    private static final String SECRET = "test-paystack-secret";
    private static final String BASE_URL = "https://api.paystack.test";

    private MockRestServiceServer server;
    private PaystackPaymentProvider provider;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        provider = new PaystackPaymentProvider(
                restTemplate,
                JsonMapper.builder().build(),
                SECRET,
                BASE_URL,
                "wema-bank"
        );
    }

    @Test
    void initializesAndVerifiesTransaction() {
        server.expect(once(), requestTo(BASE_URL + "/transaction/initialize"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andExpect(content().json("""
                        {"amount":125000,"email":"buyer@example.com","reference":"PAY-123"}
                        """))
                .andRespond(withSuccess("""
                        {"status":true,"data":{"authorization_url":"https://checkout.test/PAY-123","reference":"PAY-123"}}
                        """, MediaType.APPLICATION_JSON));

        server.expect(once(), requestTo(BASE_URL + "/transaction/verify/PAY-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":true,"data":{"status":"success","amount":125000,"reference":"PAY-123"}}
                        """, MediaType.APPLICATION_JSON));

        PaymentInitResponse initialized = provider.initializeTransaction(new PaymentRequest(
                new BigDecimal("1250.00"),
                "buyer@example.com",
                "PAY-123"
        ));

        PaymentVerificationResult verified = provider.verifyTransaction("PAY-123");

        assertThat(initialized.checkoutUrl()).isEqualTo("https://checkout.test/PAY-123");
        assertThat(verified.status()).isEqualTo(PaymentTransactionStatus.SUCCESS);
        assertThat(verified.amount()).isEqualByComparingTo("1250.00");
        server.verify();
    }

    @Test
    void createsDedicatedVirtualAccount() {
        server.expect(once(), requestTo(BASE_URL + "/customer/vendor@example.com"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withResourceNotFound());
        server.expect(once(), requestTo(BASE_URL + "/customer"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "email":"vendor@example.com",
                          "first_name":"Ada",
                          "last_name":"Vendor",
                          "phone":"2348012345678",
                          "metadata":{"customer_reference":"ORDER-123"}
                        }
                        """))
                .andRespond(withSuccess("""
                        {"status":true,"data":{"customer_code":"CUS_123"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/dedicated_account"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"customer":"CUS_123","country":"NG","preferred_bank":"wema-bank"}
                        """))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":{
                            "account_number":"0123456789",
                            "account_name":"ADA VENDOR",
                            "bank":{"name":"Wema Bank"}
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        VirtualAccountResponse account = provider.createVirtualAccount(new VirtualAccountRequest(
                "Ada Vendor",
                "vendor@example.com",
                "2348012345678",
                "ORDER-123"
        ));

        assertThat(account.accountNumber()).isEqualTo("0123456789");
        assertThat(account.bankName()).isEqualTo("Wema Bank");
        assertThat(account.accountName()).isEqualTo("ADA VENDOR");
        assertThat(account.expiresAt()).isNull();
        server.verify();
    }

    @Test
    void convertsVerifiedWebhookIntoInternalEvent() throws Exception {
        String body = """
                {"event":"charge.success","data":{"reference":"PAY-123","amount":125000,"paid_at":"2026-08-06T10:15:30Z"}}
                """;

        PaymentStatusUpdatedEvent event = provider.handleWebhookEvent(new WebhookPayload(
                body,
                Map.of("x-paystack-signature", sign(body))
        ));

        assertThat(event.relevant()).isTrue();
        assertThat(event.reference()).isEqualTo("PAY-123");
        assertThat(event.status()).isEqualTo(PaymentTransactionStatus.SUCCESS);
        assertThat(event.amount()).isEqualByComparingTo("1250.00");
    }

    @Test
    void resolvesAccountCreatesRecipientAndInitiatesPayout() {
        server.expect(once(), requestTo(BASE_URL + "/bank/resolve?account_number=0123456789&bank_code=058"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":true,"data":{"account_name":"ADA VENDOR","account_number":"0123456789"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/transferrecipient"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "type":"nuban",
                          "name":"ADA VENDOR",
                          "account_number":"0123456789",
                          "bank_code":"058",
                          "currency":"NGN"
                        }
                        """))
                .andRespond(withSuccess("""
                        {"status":true,"data":{"recipient_code":"RCP_123"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/transfer"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "source":"balance",
                          "amount":125000,
                          "recipient":"RCP_123",
                          "reference":"payout-order-1234",
                          "reason":"Order payout"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":{"transfer_code":"TRF_123","reference":"payout-order-1234","status":"pending"}
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/transfer/verify/payout-order-1234"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":{"transfer_code":"TRF_123","reference":"payout-order-1234","status":"success"}
                        }
                        """, MediaType.APPLICATION_JSON));

        ResolvedBankAccount account = provider.resolveBankAccount(
                new BankAccountLookupRequest("0123456789", "058")
        );
        PayoutRecipientResponse recipient = provider.createPayoutRecipient(new PayoutRecipientRequest(
                account.accountName(),
                account.accountNumber(),
                account.bankCode()
        ));
        PayoutResponse payout = provider.initiatePayout(new PayoutRequest(
                recipient.recipientId(),
                new BigDecimal("1250.00"),
                "payout-order-1234",
                "Order payout"
        ));
        PayoutStatusUpdatedEvent verified = provider.verifyPayout("payout-order-1234");

        assertThat(account.accountName()).isEqualTo("ADA VENDOR");
        assertThat(recipient.recipientId()).isEqualTo("RCP_123");
        assertThat(payout.status()).isEqualTo(PayoutStatus.PENDING);
        assertThat(verified.status()).isEqualTo(PayoutStatus.SUCCESS);
        server.verify();
    }

    @Test
    void convertsVerifiedTransferWebhookIntoInternalEvent() throws Exception {
        String body = """
                {
                  "event":"transfer.reversed",
                  "data":{"reference":"payout-order-1234","transfer_code":"TRF_123"}
                }
                """;

        PayoutStatusUpdatedEvent event = provider.handlePayoutWebhookEvent(new WebhookPayload(
                body,
                Map.of("x-paystack-signature", sign(body))
        ));

        assertThat(event.relevant()).isTrue();
        assertThat(event.reference()).isEqualTo("payout-order-1234");
        assertThat(event.status()).isEqualTo(PayoutStatus.REVERSED);
    }

    @Test
    void rejectsInvalidPaystackTransferReferenceBeforeCallingProvider() {
        assertThatThrownBy(() -> provider.initiatePayout(new PayoutRequest(
                "RCP_123",
                new BigDecimal("1250.00"),
                "PAYOUT-1",
                "Order payout"
        ))).hasMessageContaining("Payout reference must be 16-50");

        server.verify();
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
