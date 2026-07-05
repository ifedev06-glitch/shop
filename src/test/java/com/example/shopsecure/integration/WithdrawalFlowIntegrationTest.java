package com.example.shopsecure.integration;

import com.example.shopsecure.entity.*;
import com.example.shopsecure.repository.*;
import com.example.shopsecure.service.PaystackService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@Transactional
class WithdrawalFlowIntegrationTest {

    @TestConfiguration
    static class MockConfig {
        @Bean
        @Primary
        PaystackService paystackService() {
            PaystackService mock = mock(PaystackService.class);
            when(mock.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);
            Map<String, Object> transferResponse = new HashMap<>();
            transferResponse.put("transfer_code", "TRF_test_123");
            when(mock.initiateTransfer(anyString(), any(), anyString(), anyString()))
                    .thenReturn(transferResponse);
            return mock;
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private BankAccountRepository bankAccountRepository;
    @Autowired
    private WithdrawalRepository withdrawalRepository;

    private Business business;
    private Wallet wallet;
    private User vendor;
    private BankAccount bankAccount;

    @BeforeEach
    void setUp() {
        vendor = User.builder()
                .firstname("Test").lastname("Vendor")
                .email("vendor@shopsecure.com")
                .password("encoded")
                .role(Role.BUSINESS)
                .transactionPin(passwordEncoder.encode("1234"))
                .createdAt(LocalDateTime.now())
                .build();
        userRepository.save(vendor);

        business = Business.builder()
                .businessName("Test Store").owner(vendor)
                .createdAt(LocalDateTime.now())
                .build();
        businessRepository.save(business);

        wallet = Wallet.builder()
                .business(business)
                .balance(new BigDecimal("10000.00"))
                .totalWithdrawn(BigDecimal.ZERO)
                .currency("NGN")
                .status(WalletStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();
        walletRepository.save(wallet);

        bankAccount = BankAccount.builder()
                .business(business)
                .accountName("Test Account")
                .accountNumber("0123456789")
                .bankCode("011")
                .bankName("Test Bank")
                .recipientCode("RCP_test_001")
                .createdAt(LocalDateTime.now())
                .build();
        bankAccountRepository.save(bankAccount);
    }

    @Test
    @WithMockUser(username = "vendor@shopsecure.com", roles = "BUSINESS")
    void withdraw_success() throws Exception {
        String json = mockMvc.perform(post("/api/vendor/withdrawals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":500.00,"bankAccountId":%d,"pin":"1234"}
                        """.formatted(bankAccount.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json).contains("reference");
        assertThat(json).contains("\"status\":\"PENDING\"");

        Wallet updated = walletRepository.findByBusiness_Id(business.getId()).orElseThrow();
        assertThat(updated.getBalance()).isEqualByComparingTo("9500.00");
    }

    @Test
    @WithMockUser(username = "vendor@shopsecure.com", roles = "BUSINESS")
    void withdraw_insufficientBalance() throws Exception {
        mockMvc.perform(post("/api/vendor/withdrawals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":999999,"bankAccountId":%d,"pin":"1234"}
                        """.formatted(bankAccount.getId())))
                .andExpect(status().isBadRequest());

        Wallet updated = walletRepository.findByBusiness_Id(business.getId()).orElseThrow();
        assertThat(updated.getBalance()).isEqualByComparingTo("10000.00");
    }

    @Test
    void webhook_transferSuccess() throws Exception {
        Withdrawal withdrawal = Withdrawal.builder()
                .business(business)
                .amount(new BigDecimal("500.00"))
                .reference("WD-TEST-REF")
                .transferCode("TRF_webhook_test")
                .status(WithdrawalStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        withdrawalRepository.save(withdrawal);

        wallet.setBalance(new BigDecimal("9500.00"));
        walletRepository.save(wallet);

        String payload = """
                {"event":"transfer.success","data":{"transfer_code":"TRF_webhook_test"}}
                """;

        mockMvc.perform(post("/api/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-paystack-signature", "test-sig")
                .content(payload))
                .andExpect(status().isOk());

        Withdrawal updated = withdrawalRepository.findByReference("WD-TEST-REF").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WithdrawalStatus.SUCCESS);
    }

    @Test
    void webhook_transferFailed() throws Exception {
        Withdrawal withdrawal = Withdrawal.builder()
                .business(business)
                .amount(new BigDecimal("500.00"))
                .reference("WD-TEST-REF-FAIL")
                .transferCode("TRF_fail_test")
                .status(WithdrawalStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        withdrawalRepository.save(withdrawal);

        wallet.setBalance(new BigDecimal("9500.00"));
        walletRepository.save(wallet);

        String payload = """
                {"event":"transfer.failed","data":{"transfer_code":"TRF_fail_test","reason":"Insufficient funds"}}
                """;

        mockMvc.perform(post("/api/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-paystack-signature", "test-sig")
                .content(payload))
                .andExpect(status().isOk());

        Withdrawal updated = withdrawalRepository.findByReference("WD-TEST-REF-FAIL").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(updated.getFailureReason()).isEqualTo("Insufficient funds");

        Wallet w = walletRepository.findByBusiness_Id(business.getId()).orElseThrow();
        assertThat(w.getBalance()).isEqualByComparingTo("10000.00");
    }

    @Test
    @WithMockUser(username = "vendor@shopsecure.com", roles = "BUSINESS")
    void withdrawAndWebhook_fullFlow() throws Exception {
        mockMvc.perform(post("/api/vendor/withdrawals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":500.00,"bankAccountId":%d,"pin":"1234"}
                        """.formatted(bankAccount.getId())))
                .andExpect(status().isOk());

        Withdrawal withdrawal = withdrawalRepository.findAll().stream()
                .filter(w -> w.getStatus() == WithdrawalStatus.PENDING)
                .findFirst().orElseThrow();

        String payload = """
                {"event":"transfer.success","data":{"transfer_code":"%s"}}
                """.formatted(withdrawal.getTransferCode());

        mockMvc.perform(post("/api/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-paystack-signature", "test-sig")
                .content(payload))
                .andExpect(status().isOk());

        Withdrawal updated = withdrawalRepository.findById(withdrawal.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WithdrawalStatus.SUCCESS);
    }
}
