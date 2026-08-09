package com.example.shopsecure.provider.payment;

import com.example.shopsecure.exception.PaymentException;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.provider.common.WebhookPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
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
                        {
                          "amount":125000,
                          "email":"buyer@example.com",
                          "reference":"PAY-123",
                          "currency":"NGN",
                          "callback_url":"https://shopsecure.test/orders/token/share-123",
                          "metadata":{"order_id":123}
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":{
                            "authorization_url":"https://checkout.test/PAY-123",
                            "access_code":"ACCESS_PAY_123",
                            "reference":"PAY-123"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(once(), requestTo(BASE_URL + "/transaction/verify/PAY-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":{
                            "id":982351,
                            "status":"success",
                            "amount":125000,
                            "currency":"NGN",
                            "channel":"card",
                            "reference":"PAY-123",
                            "paid_at":"2026-08-08T10:14:30Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/transaction/verify/PAY-MISSING-REFERENCE"))
                .andRespond(withSuccess("""
                        {"status":true,"data":{"id":982352,"status":"success","amount":125000,"currency":"NGN"}}
                        """, MediaType.APPLICATION_JSON));

        PaymentInitResponse initialized = provider.initializeTransaction(new PaymentRequest(
                new BigDecimal("1250.00"),
                "buyer@example.com",
                "PAY-123",
                "NGN",
                "https://shopsecure.test/orders/token/share-123",
                Map.of("order_id", 123)
        ));

        PaymentVerificationResult verified = provider.verifyTransaction("PAY-123");

        assertThat(initialized.checkoutUrl()).isEqualTo("https://checkout.test/PAY-123");
        assertThat(initialized.accessCode()).isEqualTo("ACCESS_PAY_123");
        assertThat(verified.status()).isEqualTo(PaymentTransactionStatus.SUCCESS);
        assertThat(verified.amount()).isEqualByComparingTo("1250.00");
        assertThat(verified.eventId()).isEqualTo("982351");
        assertThat(verified.reference()).isEqualTo("PAY-123");
        assertThat(verified.currency()).isEqualTo("NGN");
        assertThat(verified.channel()).isEqualTo("card");
        assertThat(verified.occurredAt()).isEqualTo(Instant.parse("2026-08-08T10:14:30Z"));
        assertThatThrownBy(() -> provider.verifyTransaction("PAY-MISSING-REFERENCE"))
                .isInstanceOf(PaymentException.class)
                .hasMessage("Payment provider response is missing reference");
        server.verify();
    }

    @Test
    void listsEnabledNigerianNubanBanks() {
        server.expect(once(), requestTo(BASE_URL + "/bank?country=nigeria&currency=NGN&type=nuban"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":[
                            {"name":"Guaranty Trust Bank","code":"058","active":true},
                            {"name":"Disabled Bank","code":"999","active":false},
                            {"name":"Wema Bank","code":"035","active":true}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<Bank> banks = provider.listBanks();

        assertThat(banks).containsExactly(
                new Bank("Guaranty Trust Bank", "058"),
                new Bank("Wema Bank", "035")
        );
        server.verify();

        PaystackPaymentProvider testModeProvider = new PaystackPaymentProvider(
                new RestTemplate(),
                JsonMapper.builder().build(),
                "sk_test_example",
                BASE_URL,
                ""
        );
        assertThat(testModeProvider.listBanks())
                .containsExactly(new Bank("Paystack Test Bank", "001"));
        assertThatThrownBy(() -> testModeProvider.resolveBankAccount(
                new BankAccountLookupRequest("0123456789", "058")
        )).isInstanceOf(BusinessException.class)
                .hasMessage("Use Paystack Test Bank while Paystack is in test mode");
    }

    @Test
    void usesDocumentedTransferBankCodeForPaystackTestBankRecipient() {
        RestTemplate testRestTemplate = new RestTemplate();
        MockRestServiceServer testServer = MockRestServiceServer.bindTo(testRestTemplate).build();
        PaystackPaymentProvider testModeProvider = new PaystackPaymentProvider(
                testRestTemplate,
                JsonMapper.builder().build(),
                "sk_test_example",
                BASE_URL,
                ""
        );
        testServer.expect(once(), requestTo(BASE_URL + "/transferrecipient"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "type":"nuban",
                          "name":"PAYSTACK TEST BANK",
                          "account_number":"0000000000",
                          "bank_code":"057",
                          "currency":"NGN"
                        }
                        """))
                .andRespond(withSuccess("""
                        {"status":true,"data":{"recipient_code":"RCP_TEST_123"}}
                        """, MediaType.APPLICATION_JSON));

        PayoutRecipientResponse recipient = testModeProvider.createPayoutRecipient(
                new PayoutRecipientRequest("PAYSTACK TEST BANK", "0000000000", "001")
        );

        assertThat(recipient.recipientId()).isEqualTo("RCP_TEST_123");
        testServer.verify();
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
                        {
                          "customer":"CUS_123",
                          "country":"NG",
                          "first_name":"Ada",
                          "last_name":"Vendor",
                          "phone":"2348012345678",
                          "preferred_bank":"wema-bank"
                        }
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
    void suppliesRequiredDetailsWhenAssigningAccountToExistingCustomer() {
        server.expect(once(), requestTo(BASE_URL + "/customer/vendor@example.com"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":true,"data":{"customer_code":"CUS_EXISTING","phone":null}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/dedicated_account"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "customer":"CUS_EXISTING",
                          "country":"NG",
                          "first_name":"Ada",
                          "last_name":"Vendor",
                          "phone":"2348012345678",
                          "preferred_bank":"wema-bank"
                        }
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
        server.verify();
    }

    @Test
    void convertsVerifiedWebhookIntoInternalEvent() throws Exception {
        String body = """
                {
                  "event":"charge.success",
                  "data":{
                    "id":982351,
                    "reference":"paystack-generated-reference",
                    "amount":125000,
                    "currency":"NGN",
                    "channel":"dedicated_nuban",
                    "paid_at":"2026-08-06T10:15:30Z",
                    "created_at":"2026-08-06T10:14:30Z",
                    "authorization":{"receiver_bank_account_number":"0123456789"}
                  }
                }
                """;

        PaymentStatusUpdatedEvent event = provider.handleWebhookEvent(new WebhookPayload(
                body,
                Map.of("x-paystack-signature", sign(body))
        ));

        assertThat(event.relevant()).isTrue();
        assertThat(event.eventId()).isEqualTo("982351");
        assertThat(event.reference()).isEqualTo("paystack-generated-reference");
        assertThat(event.status()).isEqualTo(PaymentTransactionStatus.SUCCESS);
        assertThat(event.amount()).isEqualByComparingTo("1250.00");
        assertThat(event.currency()).isEqualTo("NGN");
        assertThat(event.channel()).isEqualTo("dedicated_nuban");
        assertThat(event.receiverAccountNumber()).isEqualTo("0123456789");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-06T10:15:30Z"));
    }

    @Test
    void extractsDvaReceiverFromSupportedPaystackLocations() throws Exception {
        assertThat(List.of(
                handleSuccessfulDvaWith("""
                        "authorization":{"receiver_bank_account_number":"0000000001"}
                        """).receiverAccountNumber(),
                handleSuccessfulDvaWith("""
                        "authorization":{"receiver_account_number":"0000000002"}
                        """).receiverAccountNumber(),
                handleSuccessfulDvaWith("""
                        "dedicated_account":{"account_number":"0000000003"}
                        """).receiverAccountNumber()
        )).containsExactly("0000000001", "0000000002", "0000000003");
    }

    @Test
    void acceptsCheckoutCardChargeWithoutTreatingMetadataAsAReceiverAccount() throws Exception {
        String body = """
                {
                  "event":"charge.success",
                  "data":{
                    "id":982351,
                    "reference":"card-reference",
                    "amount":125000,
                    "currency":"NGN",
                    "channel":"card",
                    "metadata":{"receiver_account_number":"0000000004"}
                  }
                }
                """;

        PaymentStatusUpdatedEvent event = provider.handleWebhookEvent(new WebhookPayload(
                body,
                Map.of("x-paystack-signature", sign(body))
        ));

        assertThat(event.relevant()).isTrue();
        assertThat(event.channel()).isEqualTo("card");
        assertThat(event.receiverAccountNumber()).isNull();
    }

    @Test
    void rejectsSuccessfulChargeWithoutAnAmount() throws Exception {
        String body = """
                {
                  "event":"charge.success",
                  "data":{
                    "id":982351,
                    "reference":"paystack-generated-reference",
                    "currency":"NGN",
                    "channel":"bank_transfer"
                  }
                }
                """;
        String signature = sign(body);

        assertThatThrownBy(() -> provider.handleWebhookEvent(new WebhookPayload(
                body,
                Map.of("x-paystack-signature", signature)
        )))
                .isInstanceOf(PaymentException.class)
                .hasMessage("Invalid payment webhook payload");
    }

    @Test
    void rejectsPaymentWebhookWithInvalidSignature() {
        String body = """
                {"event":"charge.success","data":{"reference":"paystack-generated-reference"}}
                """;

        assertThatThrownBy(() -> provider.handleWebhookEvent(new WebhookPayload(
                body,
                Map.of("x-paystack-signature", "invalid")
        )))
                .isInstanceOf(PaymentException.class)
                .hasMessage("Invalid webhook signature");
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
        assertThat(account.bankName()).isNull();
        assertThat(recipient.recipientId()).isEqualTo("RCP_123");
        assertThat(payout.status()).isEqualTo(PayoutStatus.PENDING);
        assertThat(verified.status()).isEqualTo(PayoutStatus.SUCCESS);
        server.verify();
    }

    @Test
    void pausesPayoutWhenPaystackRequiresOtp() {
        server.expect(once(), requestTo(BASE_URL + "/transfer"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":{
                            "transfer_code":"TRF_OTP",
                            "reference":"payout-order-otp",
                            "status":"otp"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        PayoutResponse payout = provider.initiatePayout(new PayoutRequest(
                "RCP_123",
                new BigDecimal("1250.00"),
                "payout-order-otp",
                "Order payout"
        ));

        assertThat(payout.status()).isEqualTo(PayoutStatus.REQUIRES_ACTION);
        server.verify();
    }

    @Test
    void sanitizesProviderClientErrorsOutsideCustomerLookup() {
        server.expect(once(), requestTo(BASE_URL + "/transaction/verify/PAY-MISSING"))
                .andRespond(withResourceNotFound()
                        .body("missing transaction containing " + SECRET)
                        .contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> provider.verifyTransaction("PAY-MISSING"))
                .isInstanceOf(PaymentException.class)
                .hasMessage("Payment provider request failed")
                .hasMessageNotContaining(SECRET);
        server.verify();
    }

    @Test
    void sanitizesProviderServerErrors() {
        server.expect(once(), requestTo(BASE_URL + "/transaction/verify/PAY-500"))
                .andRespond(withServerError()
                        .body("upstream failure containing " + SECRET)
                        .contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> provider.verifyTransaction("PAY-500"))
                .isInstanceOf(PaymentException.class)
                .hasMessage("Payment provider request failed")
                .hasMessageNotContaining(SECRET);
        server.verify();
    }

    @Test
    void sanitizesProviderTimeouts() {
        server.expect(once(), requestTo(BASE_URL + "/transaction/verify/PAY-TIMEOUT"))
                .andRespond(request -> {
                    throw new ResourceAccessException("timeout containing " + SECRET);
                });

        assertThatThrownBy(() -> provider.verifyTransaction("PAY-TIMEOUT"))
                .isInstanceOf(PaymentException.class)
                .hasMessage("Payment provider request failed")
                .hasMessageNotContaining(SECRET);
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

    private PaymentStatusUpdatedEvent handleSuccessfulDvaWith(String receiverLocation) throws Exception {
        String body = """
                {
                  "event":"charge.success",
                  "data":{
                    "id":982351,
                    "reference":"paystack-generated-reference",
                    "amount":125000,
                    "currency":"NGN",
                    "channel":"bank_transfer",
                    "created_at":"2026-08-06T10:14:30Z",
                    %s
                  }
                }
                """.formatted(receiverLocation);
        return provider.handleWebhookEvent(new WebhookPayload(
                body,
                Map.of("x-paystack-signature", sign(body))
        ));
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
