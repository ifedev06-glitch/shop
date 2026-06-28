package com.example.shopsecure.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.shopsecure.dto.request.WithdrawalRequest;
import com.example.shopsecure.dto.response.WithdrawalResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceTest {

    @Mock WithdrawalRepository withdrawalRepository;
    @Mock WalletRepository walletRepository;
    @Mock BankAccountRepository bankAccountRepository;
    @Mock BusinessRepository businessRepository;
    @Mock UserRepository userRepository;
    @Mock PaystackService paystackService;
    @Mock PinService pinService;

    @Captor ArgumentCaptor<Wallet> walletCaptor;
    @Captor ArgumentCaptor<Withdrawal> withdrawalCaptor;

    WithdrawalService withdrawalService;

    User seller;
    Business business;
    Wallet wallet;
    BankAccount bankAccount;
    WithdrawalRequest request;

    @BeforeEach
    void setUp() {
        withdrawalService = new WithdrawalService(
                withdrawalRepository, walletRepository, bankAccountRepository,
                businessRepository, userRepository, paystackService, pinService);

        seller = User.builder().id(1L).email("vendor@example.com")
                .transactionPin("$2a$10$encrypted").build();

        business = Business.builder()
                .id(1L).businessName("Test Store").owner(seller).build();

        wallet = Wallet.builder()
                .id(1L).business(business)
                .availableBalance(new BigDecimal("50000"))
                .processingBalance(BigDecimal.ZERO)
                .currency("NGN").status(WalletStatus.ACTIVE)
                .build();

        bankAccount = BankAccount.builder()
                .id(1L).business(business).bankName("Test Bank")
                .accountName("John Doe").accountNumber("0123456789")
                .recipientCode("RCP_test123").build();

        request = new WithdrawalRequest();
        request.setAmount(new BigDecimal("10000"));
        request.setBankAccountId(1L);
        request.setPin("1234");
    }

    private void mockSecurityContext() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("vendor@example.com");
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
    }

    // =====================
    // WITHDRAW
    // =====================

    @Test
    void withdraw_success_deductsWalletAndCreatesWithdrawal() {
        doNothing().when(pinService).verifyPin("1234");

        try (MockedStatic<SecurityContextHolder> holder = mockStatic(SecurityContextHolder.class)) {
            Authentication auth = mock(Authentication.class);
            when(auth.getName()).thenReturn("vendor@example.com");
            SecurityContext ctx = mock(SecurityContext.class);
            when(ctx.getAuthentication()).thenReturn(auth);
            holder.when(SecurityContextHolder::getContext).thenReturn(ctx);

            when(userRepository.findByEmail("vendor@example.com"))
                    .thenReturn(Optional.of(seller));
            when(businessRepository.findByOwner_Id(1L))
                    .thenReturn(Optional.of(business));
            when(walletRepository.findByBusiness_IdForUpdate(1L))
                    .thenReturn(Optional.of(wallet));
            when(bankAccountRepository.findById(1L))
                    .thenReturn(Optional.of(bankAccount));
            when(walletRepository.save(any(Wallet.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(withdrawalRepository.save(any(Withdrawal.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(paystackService.initiateTransfer(
                    eq("RCP_test123"), eq(new BigDecimal("10000")),
                    anyString(), eq("Vendor Withdrawal")))
                    .thenReturn(Map.of("transfer_code", "TRF_test123"));
            when(withdrawalRepository.findByReference(anyString()))
                    .thenAnswer(inv -> {
                        String ref = inv.getArgument(0);
                        return Optional.of(Withdrawal.builder()
                                .id(1L).business(business)
                                .amount(new BigDecimal("10000"))
                                .reference(ref)
                                .status(WithdrawalStatus.PENDING)
                                .createdAt(LocalDateTime.now()).build());
                    });

            WithdrawalResponse response = withdrawalService.withdraw(request);

            assertNotNull(response);
            assertEquals(0, new BigDecimal("10000").compareTo(response.getAmount()));
            assertTrue(response.getReference().startsWith("WD-"));
            assertEquals("TRF_test123", response.getTransferCode());

            verify(walletRepository).save(walletCaptor.capture());
            assertEquals(0, new BigDecimal("40000")
                    .compareTo(walletCaptor.getValue().getAvailableBalance()));

            verify(withdrawalRepository, times(2)).save(any(Withdrawal.class));
        }
    }

    @Test
    void withdraw_insufficientBalance_throws() {
        wallet.setAvailableBalance(new BigDecimal("5000"));

        doNothing().when(pinService).verifyPin("1234");

        try (MockedStatic<SecurityContextHolder> holder = mockStatic(SecurityContextHolder.class)) {
            Authentication auth = mock(Authentication.class);
            when(auth.getName()).thenReturn("vendor@example.com");
            SecurityContext ctx = mock(SecurityContext.class);
            when(ctx.getAuthentication()).thenReturn(auth);
            holder.when(SecurityContextHolder::getContext).thenReturn(ctx);

            when(userRepository.findByEmail("vendor@example.com"))
                    .thenReturn(Optional.of(seller));
            when(businessRepository.findByOwner_Id(1L))
                    .thenReturn(Optional.of(business));
            when(walletRepository.findByBusiness_IdForUpdate(1L))
                    .thenReturn(Optional.of(wallet));
            when(bankAccountRepository.findById(1L))
                    .thenReturn(Optional.of(bankAccount));

            assertThrows(BusinessException.class,
                    () -> withdrawalService.withdraw(request));

            verify(walletRepository, never()).save(any());
            verify(withdrawalRepository, never()).save(any());
        }
    }

    @Test
    void withdraw_unauthorizedBankAccount_throws() {
        Business otherBusiness = Business.builder().id(99L).build();
        bankAccount.setBusiness(otherBusiness);

        doNothing().when(pinService).verifyPin("1234");

        try (MockedStatic<SecurityContextHolder> holder = mockStatic(SecurityContextHolder.class)) {
            Authentication auth = mock(Authentication.class);
            when(auth.getName()).thenReturn("vendor@example.com");
            SecurityContext ctx = mock(SecurityContext.class);
            when(ctx.getAuthentication()).thenReturn(auth);
            holder.when(SecurityContextHolder::getContext).thenReturn(ctx);

            when(userRepository.findByEmail("vendor@example.com"))
                    .thenReturn(Optional.of(seller));
            when(businessRepository.findByOwner_Id(1L))
                    .thenReturn(Optional.of(business));
            when(walletRepository.findByBusiness_IdForUpdate(1L))
                    .thenReturn(Optional.of(wallet));
            when(bankAccountRepository.findById(1L))
                    .thenReturn(Optional.of(bankAccount));

            assertThrows(BusinessException.class,
                    () -> withdrawalService.withdraw(request));
            verify(walletRepository, never()).save(any());
            verify(withdrawalRepository, never()).save(any());
        }
    }

    @Test
    void withdraw_paystackNetworkFailure_setsFailureReason() {
        doNothing().when(pinService).verifyPin("1234");

        Withdrawal savedWithdrawal = Withdrawal.builder()
                .id(1L).business(business).amount(new BigDecimal("10000"))
                .reference("WD-TEST").status(WithdrawalStatus.PENDING)
                .createdAt(LocalDateTime.now()).build();

        try (MockedStatic<SecurityContextHolder> holder = mockStatic(SecurityContextHolder.class)) {
            Authentication auth = mock(Authentication.class);
            when(auth.getName()).thenReturn("vendor@example.com");
            SecurityContext ctx = mock(SecurityContext.class);
            when(ctx.getAuthentication()).thenReturn(auth);
            holder.when(SecurityContextHolder::getContext).thenReturn(ctx);

            when(userRepository.findByEmail("vendor@example.com"))
                    .thenReturn(Optional.of(seller));
            when(businessRepository.findByOwner_Id(1L))
                    .thenReturn(Optional.of(business));
            when(walletRepository.findByBusiness_IdForUpdate(1L))
                    .thenReturn(Optional.of(wallet));
            when(bankAccountRepository.findById(1L))
                    .thenReturn(Optional.of(bankAccount));
            when(walletRepository.save(any(Wallet.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(withdrawalRepository.save(any(Withdrawal.class)))
                    .thenReturn(savedWithdrawal);
            when(paystackService.initiateTransfer(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Network timeout"));
            when(withdrawalRepository.findByReference(anyString()))
                    .thenReturn(Optional.of(savedWithdrawal));

            WithdrawalResponse response = withdrawalService.withdraw(request);

            assertNull(response.getTransferCode());
            verify(withdrawalRepository, times(2)).save(any(Withdrawal.class));
            assertEquals("Initial network timeout: awaiting reconciliation.",
                    savedWithdrawal.getFailureReason());
        }
    }

    @Test
    void withdraw_paystackReturnsNoTransferCode_savesNull() {
        doNothing().when(pinService).verifyPin("1234");

        try (MockedStatic<SecurityContextHolder> holder = mockStatic(SecurityContextHolder.class)) {
            Authentication auth = mock(Authentication.class);
            when(auth.getName()).thenReturn("vendor@example.com");
            SecurityContext ctx = mock(SecurityContext.class);
            when(ctx.getAuthentication()).thenReturn(auth);
            holder.when(SecurityContextHolder::getContext).thenReturn(ctx);

            when(userRepository.findByEmail("vendor@example.com"))
                    .thenReturn(Optional.of(seller));
            when(businessRepository.findByOwner_Id(1L))
                    .thenReturn(Optional.of(business));
            when(walletRepository.findByBusiness_IdForUpdate(1L))
                    .thenReturn(Optional.of(wallet));
            when(bankAccountRepository.findById(1L))
                    .thenReturn(Optional.of(bankAccount));
            when(walletRepository.save(any(Wallet.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(withdrawalRepository.save(any(Withdrawal.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(paystackService.initiateTransfer(any(), any(), any(), any()))
                    .thenReturn(Map.of()); // no transfer_code in response
            when(withdrawalRepository.findByReference(anyString()))
                    .thenAnswer(inv -> {
                        String ref = inv.getArgument(0);
                        return Optional.of(Withdrawal.builder()
                                .id(1L).business(business)
                                .amount(new BigDecimal("10000"))
                                .reference(ref)
                                .status(WithdrawalStatus.PENDING)
                                .createdAt(LocalDateTime.now()).build());
                    });

            WithdrawalResponse response = withdrawalService.withdraw(request);

            assertNull(response.getTransferCode());
            verify(withdrawalRepository, times(2)).save(withdrawalCaptor.capture());
            Withdrawal finalSave = withdrawalCaptor.getAllValues().get(1);
            assertEquals("Initial network timeout: awaiting reconciliation.",
                    finalSave.getFailureReason());
        }
    }

    // =====================
    // TRANSFER SUCCESS WEBHOOK
    // =====================

    @Test
    void handleTransferSuccess_updatesStatus() {
        Withdrawal withdrawal = Withdrawal.builder()
                .id(1L).business(business).amount(new BigDecimal("10000"))
                .reference("WD-TEST").status(WithdrawalStatus.PENDING)
                .createdAt(LocalDateTime.now()).build();

        when(withdrawalRepository.findByTransferCodeForUpdate("TRF_test123"))
                .thenReturn(Optional.of(withdrawal));
        when(withdrawalRepository.save(any(Withdrawal.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        withdrawalService.handleTransferSuccess("TRF_test123");

        assertEquals(WithdrawalStatus.SUCCESS, withdrawal.getStatus());
    }

    @Test
    void handleTransferSuccess_alreadyProcessed_skips() {
        Withdrawal withdrawal = Withdrawal.builder()
                .id(1L).business(business).amount(new BigDecimal("10000"))
                .reference("WD-TEST").status(WithdrawalStatus.SUCCESS)
                .createdAt(LocalDateTime.now()).build();

        when(withdrawalRepository.findByTransferCodeForUpdate("TRF_test123"))
                .thenReturn(Optional.of(withdrawal));

        withdrawalService.handleTransferSuccess("TRF_test123");

        verify(withdrawalRepository, never()).save(any());
    }

    // =====================
    // TRANSFER FAILED WEBHOOK
    // =====================

    @Test
    void handleTransferFailed_refundsWallet() {
        wallet.setAvailableBalance(new BigDecimal("40000")); // after initial deduction

        Withdrawal withdrawal = Withdrawal.builder()
                .id(1L).business(business).amount(new BigDecimal("10000"))
                .reference("WD-TEST").status(WithdrawalStatus.PENDING)
                .createdAt(LocalDateTime.now()).build();

        when(withdrawalRepository.findByTransferCodeForUpdate("TRF_test123"))
                .thenReturn(Optional.of(withdrawal));
        when(walletRepository.findByBusiness_IdForUpdate(1L))
                .thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(withdrawalRepository.save(any(Withdrawal.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        withdrawalService.handleTransferFailed("TRF_test123", "Insufficient funds");

        assertEquals(WithdrawalStatus.FAILED, withdrawal.getStatus());
        assertEquals("Insufficient funds", withdrawal.getFailureReason());
        // restored from 40000 back to 50000
        assertEquals(0, new BigDecimal("50000")
                .compareTo(wallet.getAvailableBalance()));
    }

    @Test
    void handleTransferFailed_alreadyFailed_skips() {
        Withdrawal withdrawal = Withdrawal.builder()
                .id(1L).business(business).amount(new BigDecimal("10000"))
                .reference("WD-TEST").status(WithdrawalStatus.FAILED)
                .createdAt(LocalDateTime.now()).build();

        when(withdrawalRepository.findByTransferCodeForUpdate("TRF_test123"))
                .thenReturn(Optional.of(withdrawal));

        withdrawalService.handleTransferFailed("TRF_test123", "Insufficient funds");

        verify(walletRepository, never()).save(any());
        verify(withdrawalRepository, never()).save(any());
    }
}
