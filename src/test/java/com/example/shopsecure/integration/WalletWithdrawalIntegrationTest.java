package com.example.shopsecure.integration;

import com.example.shopsecure.ShopsecureApplication;
import com.example.shopsecure.dto.request.CreateWalletWithdrawalRequest;
import com.example.shopsecure.dto.request.ResolveBankAccountRequest;
import com.example.shopsecure.dto.request.WalletWithdrawalChallengeRequest;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.PayoutRejectedException;
import com.example.shopsecure.provider.payment.*;
import com.example.shopsecure.repository.*;
import com.example.shopsecure.service.WalletWithdrawalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {
                ShopsecureApplication.class,
                TestConfig.class,
                ReconciliationSettlementTestConfig.class
        },
        properties = {
                "spring.datasource.url=jdbc:h2:mem:withdrawaltest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "wallet.withdrawal.reconciliation.initial-delay-ms=3600000",
                "wallet.withdrawal.reconciliation.delay-ms=3600000"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@WithMockUser(username = WalletWithdrawalIntegrationTest.EMAIL, roles = "USER")
class WalletWithdrawalIntegrationTest {

    static final String EMAIL = "seller.wallet@example.com";
    private static final String ACCOUNT_NUMBER = "0123456789";
    private static final String BANK_CODE = "058";

    @Autowired
    private WalletWithdrawalService withdrawalService;
    @Autowired
    private PayoutProvider payoutProvider;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private WalletTransactionRepository transactionRepository;
    @Autowired
    private WalletWithdrawalRepository withdrawalRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderSettlementRepository settlementRepository;
    @Autowired
    private WithdrawalBankAccountRepository bankAccountRepository;
    @Autowired
    private OtpChallengeRepository otpChallengeRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        reset(payoutProvider);
        when(payoutProvider.listBanks()).thenReturn(List.of(new Bank("GTBank", BANK_CODE)));
        doAnswer(invocation -> {
            BankAccountLookupRequest request = invocation.getArgument(0);
            return new ResolvedBankAccount(
                    "SELLER USER",
                    request.accountNumber(),
                    request.bankCode(),
                    "GTBank"
            );
        }).when(payoutProvider).resolveBankAccount(any());
        when(payoutProvider.createPayoutRecipient(any()))
                .thenReturn(new PayoutRecipientResponse("RCP_WITHDRAWAL"));
        createWallet("100000.00");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void savesVerifiedBankAndReservesOnlyAvailableBalanceIdempotently() {
        var bank = saveBank();
        assertThat(bank.accountNumberMasked()).isEqualTo("******6789");
        assertThat(bank.accountName()).isEqualTo("SELLER USER");

        when(payoutProvider.initiatePayout(any())).thenAnswer(invocation -> {
            PayoutRequest request = invocation.getArgument(0);
            return new PayoutResponse("TRF_WITHDRAWAL", request.reference(), PayoutStatus.PENDING);
        });

        var authorization = authorize("30000.00");
        var created = withdrawalService.createWithdrawal(
                request("30000.00", authorization),
                "withdrawal-key-1"
        );
        var replay = withdrawalService.createWithdrawal(
                request("30000.00", authorization),
                "withdrawal-key-1"
        );

        assertThat(created.withdrawalId()).isEqualTo(replay.withdrawalId());
        assertThat(created.status()).isEqualTo(WalletWithdrawalStatus.PENDING);
        assertThat(wallet().getBalance()).isEqualByComparingTo("70000.00");
        assertThat(transactionRepository.findAll())
                .singleElement()
                .satisfies(transaction -> {
                    assertThat(transaction.getTransactionType())
                            .isEqualTo(WalletTransactionType.WITHDRAWAL_DEBIT);
                    assertThat(transaction.getBalanceBefore()).isEqualByComparingTo("100000.00");
                    assertThat(transaction.getBalanceAfter()).isEqualByComparingTo("70000.00");
                });
        verify(payoutProvider, times(1)).initiatePayout(any(PayoutRequest.class));

        assertThatThrownBy(() -> withdrawalService.createWithdrawal(
                request("31000.00", authorization),
                "withdrawal-key-1"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("Idempotency key was already used for a different withdrawal amount");
    }

    @Test
    void successfulInitiationWithoutRecipientCodeWaitsForExactVerification() {
        saveBank();
        when(payoutProvider.initiatePayout(any())).thenAnswer(invocation -> {
            PayoutRequest request = invocation.getArgument(0);
            return new PayoutResponse(
                    "TRF_NUMERIC_RECIPIENT",
                    request.reference(),
                    PayoutStatus.SUCCESS,
                    request.amount(),
                    "NGN",
                    null
            );
        });
        var authorization = authorize("20000.00");

        var withdrawal = withdrawalService.createWithdrawal(
                request("20000.00", authorization),
                "numeric-recipient-initiation"
        );

        assertThat(withdrawal.status()).isEqualTo(WalletWithdrawalStatus.PENDING);
        WalletWithdrawal persisted = withdrawalRepository.findById(withdrawal.withdrawalId()).orElseThrow();
        assertThat(persisted.getTransferId()).isEqualTo("TRF_NUMERIC_RECIPIENT");
        assertThat(persisted.getCompletedAt()).isNull();
        assertThat(wallet().getBalance()).isEqualByComparingTo("80000.00");
    }

    @Test
    void walletInitiationCannotReuseAnOrderSettlementTransferId() {
        saveBank();
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        Instant now = Instant.now();
        Order order = orderRepository.save(Order.builder()
                .itemAmount(new BigDecimal("20000.00"))
                .currency("NGN")
                .itemDescription("Cross-domain payout identity")
                .creatorFirstName(user.getFirstname())
                .creatorLastName(user.getLastname())
                .creatorPhoneNumber(user.getPhoneNumber())
                .creatorEmail(user.getEmail())
                .creatorAddressLine1("1 Test Street")
                .creatorAddressLine2("")
                .creatorCity("Lagos")
                .creatorState("Lagos")
                .creatorCountry("NG")
                .creatorPostalCode("100001")
                .creatorRole(CreatorRole.BUYER)
                .status(OrderStatus.PAYOUT_PENDING)
                .shareToken("wallet-direct-cross-domain-share")
                .creator(user)
                .totalAmount(new BigDecimal("20000.00"))
                .paymentReference("wallet-direct-cross-domain-payment")
                .paymentStatus(PaymentStatus.CONFIRMED)
                .settlementStatus(SettlementStatus.PENDING)
                .fundedAt(now)
                .createdAt(LocalDateTime.now())
                .updatedAt(now)
                .build());
        OrderSettlement orderSettlement = settlementRepository.saveAndFlush(OrderSettlement.builder()
                .order(order)
                .settlementType(SettlementType.EXTERNAL_PAYOUT)
                .amount(new BigDecimal("20000.00"))
                .status(SettlementStatus.PENDING)
                .idempotencyKey("order-transfer-owner")
                .payoutReference("shopsecure_order_" + order.getId() + "_payout")
                .transferId("TRF_ORDER_DIRECT_OWNER")
                .payoutAttempt(1)
                .createdAt(now)
                .updatedAt(now)
                .build());
        when(payoutProvider.initiatePayout(any())).thenAnswer(invocation -> {
            PayoutRequest request = invocation.getArgument(0);
            return new PayoutResponse(
                    "TRF_ORDER_DIRECT_OWNER",
                    request.reference(),
                    PayoutStatus.SUCCESS,
                    request.amount(),
                    "NGN",
                    request.recipientId()
            );
        });
        var authorization = authorize("20000.00");

        var withdrawal = withdrawalService.createWithdrawal(
                request("20000.00", authorization),
                "wallet-cross-domain-direct"
        );

        assertThat(withdrawal.status()).isEqualTo(WalletWithdrawalStatus.REQUIRES_ACTION);
        assertThat(withdrawal.failureReason()).contains("already assigned to another payout");
        assertThat(settlementRepository.findById(orderSettlement.getId()).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.PENDING);
        assertThat(wallet().getBalance()).isEqualByComparingTo("80000.00");
    }

    @Test
    void rejectsMissingBankAndInsufficientAvailableBalanceWithoutDebiting() {
        assertThatThrownBy(() -> withdrawalService.createWithdrawal(
                new CreateWalletWithdrawalRequest(new BigDecimal("1000.00"), "missing", "000000"),
                "missing-bank-key"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("Add a verified withdrawal bank account first");
        assertThat(wallet().getBalance()).isEqualByComparingTo("100000.00");

        saveBank();
        assertThatThrownBy(() -> withdrawalService.requestWithdrawalChallenge(
                new WalletWithdrawalChallengeRequest(new BigDecimal("100000.01"))
        )).isInstanceOf(BusinessException.class)
                .hasMessage("Insufficient available wallet balance");
        assertThat(wallet().getBalance()).isEqualByComparingTo("100000.00");
        assertThat(withdrawalRepository.count()).isZero();
        assertThat(transactionRepository.count()).isZero();
        verify(payoutProvider, never()).initiatePayout(any(PayoutRequest.class));
    }

    @Test
    void confirmedFailureRefundsReservedBalanceExactlyOnce() {
        saveBank();
        when(payoutProvider.initiatePayout(any())).thenAnswer(invocation -> {
            PayoutRequest request = invocation.getArgument(0);
            return new PayoutResponse("TRF_FAILED", request.reference(), PayoutStatus.PENDING);
        });
        var authorization = authorize("25000.00");
        var withdrawal = withdrawalService.createWithdrawal(
                request("25000.00", authorization),
                "failure-key"
        );

        PayoutStatusUpdatedEvent failed = new PayoutStatusUpdatedEvent(
                withdrawal.reference(),
                "TRF_FAILED",
                PayoutStatus.FAILED,
                Instant.now(),
                true
        );
        assertThat(withdrawalService.applyPayoutStatus(failed)).isTrue();
        assertThat(withdrawalService.applyPayoutStatus(failed)).isTrue();

        assertThat(wallet().getBalance()).isEqualByComparingTo("100000.00");
        assertThat(withdrawalRepository.findById(withdrawal.withdrawalId()).orElseThrow().getStatus())
                .isEqualTo(WalletWithdrawalStatus.FAILED);
        assertThat(transactionRepository.findAll())
                .extracting(WalletTransaction::getTransactionType)
                .containsExactlyInAnyOrder(
                        WalletTransactionType.WITHDRAWAL_DEBIT,
                        WalletTransactionType.WITHDRAWAL_REFUND
                );
    }

    @Test
    void definitiveProviderRejectionRefundsReservedBalanceImmediatelyAndExactlyOnce() {
        saveBank();
        when(payoutProvider.initiatePayout(any())).thenThrow(new PayoutRejectedException());
        var authorization = authorize("25000.00");

        var rejected = withdrawalService.createWithdrawal(
                request("25000.00", authorization),
                "provider-rejection-key"
        );
        var replay = withdrawalService.createWithdrawal(
                request("25000.00", authorization),
                "provider-rejection-key"
        );

        assertThat(rejected.status()).isEqualTo(WalletWithdrawalStatus.FAILED);
        assertThat(replay.withdrawalId()).isEqualTo(rejected.withdrawalId());
        assertThat(replay.status()).isEqualTo(WalletWithdrawalStatus.FAILED);
        assertThat(wallet().getBalance()).isEqualByComparingTo("100000.00");
        assertThat(transactionRepository.findAll())
                .extracting(WalletTransaction::getTransactionType)
                .containsExactlyInAnyOrder(
                        WalletTransactionType.WITHDRAWAL_DEBIT,
                        WalletTransactionType.WITHDRAWAL_REFUND
                );
        verify(payoutProvider, times(1)).initiatePayout(any(PayoutRequest.class));
    }

    @Test
    void failedFullBalanceWithdrawalRefundsFromZeroBalance() {
        saveBank();
        when(payoutProvider.initiatePayout(any())).thenAnswer(invocation -> {
            PayoutRequest request = invocation.getArgument(0);
            return new PayoutResponse("TRF_FULL_BALANCE", request.reference(), PayoutStatus.PENDING);
        });
        var authorization = authorize("100000.00");
        var withdrawal = withdrawalService.createWithdrawal(
                request("100000.00", authorization),
                "full-balance-key"
        );
        assertThat(wallet().getBalance()).isEqualByComparingTo("0.00");

        withdrawalService.applyPayoutStatus(new PayoutStatusUpdatedEvent(
                withdrawal.reference(),
                "TRF_FULL_BALANCE",
                PayoutStatus.FAILED,
                Instant.now(),
                true
        ));

        assertThat(wallet().getBalance()).isEqualByComparingTo("100000.00");
        assertThat(transactionRepository.findByWithdrawal_IdAndTransactionType(
                withdrawal.withdrawalId(),
                WalletTransactionType.WITHDRAWAL_REFUND
        )).isPresent();
    }

    @Test
    void reversalAfterSuccessRefundsBalanceExactlyOnce() {
        saveBank();
        when(payoutProvider.initiatePayout(any())).thenAnswer(invocation -> {
            PayoutRequest request = invocation.getArgument(0);
            return new PayoutResponse(
                    "TRF_REVERSED",
                    request.reference(),
                    PayoutStatus.SUCCESS,
                    request.amount(),
                    "NGN",
                    request.recipientId()
            );
        });
        var authorization = authorize("40000.00");
        var withdrawal = withdrawalService.createWithdrawal(
                request("40000.00", authorization),
                "reversal-key"
        );
        assertThat(withdrawal.status()).isEqualTo(WalletWithdrawalStatus.COMPLETED);
        assertThat(wallet().getBalance()).isEqualByComparingTo("60000.00");

        PayoutStatusUpdatedEvent reversed = new PayoutStatusUpdatedEvent(
                withdrawal.reference(),
                "TRF_REVERSED",
                PayoutStatus.REVERSED,
                Instant.now().plusSeconds(1),
                true
        );
        withdrawalService.applyPayoutStatus(reversed);
        withdrawalService.applyPayoutStatus(reversed);

        assertThat(wallet().getBalance()).isEqualByComparingTo("100000.00");
        assertThat(withdrawalRepository.findById(withdrawal.withdrawalId()).orElseThrow().getStatus())
                .isEqualTo(WalletWithdrawalStatus.REVERSED);
        assertThat(transactionRepository.findByWithdrawal_IdAndTransactionType(
                withdrawal.withdrawalId(),
                WalletTransactionType.WITHDRAWAL_REFUND
        )).isPresent();
    }

    @Test
    void ambiguousDispatchKeepsFundsReservedUntilVerification() {
        saveBank();
        when(payoutProvider.initiatePayout(any())).thenThrow(new RuntimeException("timeout"));
        var authorization = authorize("15000.00");
        var withdrawal = withdrawalService.createWithdrawal(
                request("15000.00", authorization),
                "ambiguous-key"
        );

        assertThat(withdrawal.status()).isEqualTo(WalletWithdrawalStatus.DISPATCH_UNKNOWN);
        assertThat(wallet().getBalance()).isEqualByComparingTo("85000.00");
        assertThat(transactionRepository.findByWithdrawal_IdAndTransactionType(
                withdrawal.withdrawalId(),
                WalletTransactionType.WITHDRAWAL_REFUND
        )).isEmpty();

        when(payoutProvider.verifyPayout(withdrawal.reference())).thenReturn(
                new PayoutStatusUpdatedEvent(
                        withdrawal.reference(),
                        "TRF_RECOVERED",
                        PayoutStatus.SUCCESS,
                        new BigDecimal("15000.00"),
                        "NGN",
                        "RCP_WITHDRAWAL",
                        Instant.now(),
                        true
                )
        );
        withdrawalService.reconcileWithdrawals();

        assertThat(withdrawalRepository.findById(withdrawal.withdrawalId()).orElseThrow().getStatus())
                .isEqualTo(WalletWithdrawalStatus.COMPLETED);
        assertThat(wallet().getBalance()).isEqualByComparingTo("85000.00");
    }

    @ParameterizedTest(name = "wrong withdrawal {3} requires review")
    @CsvSource({
            "14999.99, NGN, RCP_WITHDRAWAL, amount",
            "15000.00, USD, RCP_WITHDRAWAL, currency",
            "15000.00, NGN, RCP_OTHER, recipient"
    })
    void successfulWithdrawalRequiresExactFrozenDetails(
            String amount,
            String currency,
            String recipient,
            String mismatchField
    ) {
        saveBank();
        when(payoutProvider.initiatePayout(any())).thenAnswer(invocation -> {
            PayoutRequest request = invocation.getArgument(0);
            return new PayoutResponse("TRF_EXACT_WITHDRAWAL", request.reference(), PayoutStatus.PENDING);
        });
        var authorization = authorize("15000.00");
        var withdrawal = withdrawalService.createWithdrawal(
                request("15000.00", authorization),
                "withdrawal-exact-" + mismatchField
        );

        withdrawalService.applyPayoutStatus(new PayoutStatusUpdatedEvent(
                withdrawal.reference(),
                "TRF_EXACT_WITHDRAWAL",
                PayoutStatus.SUCCESS,
                new BigDecimal(amount),
                currency,
                recipient,
                Instant.now(),
                true
        ));

        WalletWithdrawal persisted = withdrawalRepository.findById(withdrawal.withdrawalId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(WalletWithdrawalStatus.REQUIRES_ACTION);
        assertThat(persisted.getFailureReason()).containsIgnoringCase(mismatchField);
        assertThat(persisted.getCompletedAt()).isNull();
        assertThat(wallet().getBalance()).isEqualByComparingTo("85000.00");
        assertThat(transactionRepository.findByWithdrawal_IdAndTransactionType(
                withdrawal.withdrawalId(),
                WalletTransactionType.WITHDRAWAL_REFUND
        )).isEmpty();
    }

    @Test
    void withdrawalAuthorizationIsBoundToExactAmount() {
        saveBank();
        var authorization = authorize("10000.00");

        assertThatThrownBy(() -> withdrawalService.createWithdrawal(
                request("11000.00", authorization),
                "changed-amount-key"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired verification code");

        assertThat(wallet().getBalance()).isEqualByComparingTo("100000.00");
        assertThat(withdrawalRepository.count()).isZero();
    }

    @Test
    void changingBankInvalidatesOutstandingWithdrawalAuthorization() {
        saveBank();
        var authorization = authorize("10000.00");
        doAnswer(invocation -> {
            BankAccountLookupRequest request = invocation.getArgument(0);
            return new ResolvedBankAccount(
                    "SELLER NEW BANK",
                    request.accountNumber(),
                    request.bankCode(),
                    "GTBank"
            );
        }).when(payoutProvider).resolveBankAccount(any());
        when(payoutProvider.createPayoutRecipient(any()))
                .thenReturn(new PayoutRecipientResponse("RCP_NEW_BANK"));
        withdrawalService.saveBankAccount(new ResolveBankAccountRequest("0987654321", BANK_CODE));

        assertThatThrownBy(() -> withdrawalService.createWithdrawal(
                request("10000.00", authorization),
                "changed-bank-key"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired verification code");

        assertThat(wallet().getBalance()).isEqualByComparingTo("100000.00");
        assertThat(withdrawalRepository.count()).isZero();
    }

    @Test
    void authenticatedHttpContractSavesBankAuthorizesAndCreatesWithdrawal() throws Exception {
        mockMvc.perform(put("/api/wallet/bank-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountNumber":"0123456789","bankCode":"058"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumberMasked").value("******6789"))
                .andExpect(jsonPath("$.accountName").value("SELLER USER"));

        String challengeBody = mockMvc.perform(post("/api/wallet/withdrawals/challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":12500.00}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challengeId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String challengeId = objectMapper.readTree(challengeBody).path("challengeId").asText();
        OtpChallenge stored = otpChallengeRepository.findByPublicId(challengeId).orElseThrow();
        stored.setCodeHash(passwordEncoder.encode("123456"));
        stored.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        otpChallengeRepository.saveAndFlush(stored);

        when(payoutProvider.initiatePayout(any())).thenAnswer(invocation -> {
            PayoutRequest request = invocation.getArgument(0);
            return new PayoutResponse("TRF_HTTP", request.reference(), PayoutStatus.PENDING);
        });
        mockMvc.perform(post("/api/wallet/withdrawals")
                        .header("Idempotency-Key", "http-withdrawal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount":12500.00,
                                  "challengeId":"%s",
                                  "code":"123456"
                                }
                                """.formatted(challengeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(12500.00))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.accountNumberMasked").value("******6789"));

        mockMvc.perform(get("/api/wallet/withdrawals").param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));
        mockMvc.perform(get("/api/wallet/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("WITHDRAWAL_DEBIT"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/wallet/withdrawals").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Page size must be between 1 and 100"));
        assertThat(wallet().getBalance()).isEqualByComparingTo("87500.00");
    }

    private void createWallet(String balance) {
        User user = userRepository.save(User.builder()
                .firstname("Seller")
                .lastname("User")
                .email(EMAIL)
                .phoneNumber("+2348012345678")
                .password("encoded")
                .role(Role.USER)
                .createdAt(LocalDateTime.now())
                .emailVerifiedAt(LocalDateTime.now())
                .build());
        Instant now = Instant.now();
        walletRepository.save(Wallet.builder()
                .user(user)
                .balance(new BigDecimal(balance))
                .currency("NGN")
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private com.example.shopsecure.dto.response.WithdrawalBankAccountResponse saveBank() {
        return withdrawalService.saveBankAccount(
                new ResolveBankAccountRequest(ACCOUNT_NUMBER, BANK_CODE)
        );
    }

    private WithdrawalAuthorization authorize(String amount) {
        var challenge = withdrawalService.requestWithdrawalChallenge(
                new WalletWithdrawalChallengeRequest(new BigDecimal(amount))
        );
        String code = "123456";
        OtpChallenge stored = otpChallengeRepository.findByPublicId(challenge.challengeId()).orElseThrow();
        stored.setCodeHash(passwordEncoder.encode(code));
        stored.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        otpChallengeRepository.saveAndFlush(stored);
        return new WithdrawalAuthorization(challenge.challengeId(), code);
    }

    private CreateWalletWithdrawalRequest request(String amount, WithdrawalAuthorization authorization) {
        return new CreateWalletWithdrawalRequest(
                new BigDecimal(amount),
                authorization.challengeId(),
                authorization.code()
        );
    }

    private Wallet wallet() {
        return walletRepository.findByUser_Id(userRepository.findByEmail(EMAIL).orElseThrow().getId())
                .orElseThrow();
    }

    private void cleanDatabase() {
        otpChallengeRepository.deleteAllInBatch();
        transactionRepository.deleteAllInBatch();
        withdrawalRepository.deleteAllInBatch();
        settlementRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        bankAccountRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private record WithdrawalAuthorization(String challengeId, String code) {
    }
}
