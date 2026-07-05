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
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceTest {

    @Mock WithdrawalTransactionService withdrawalTransactionService;
    @Mock WithdrawalRepository withdrawalRepository;
    @Mock WalletRepository walletRepository;
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
                withdrawalTransactionService, withdrawalRepository, walletRepository,
                null, null, null, paystackService, pinService);

        seller = User.builder().id(1L).email("vendor@example.com")
                .transactionPin("$2a$10$encrypted").build();

        business = Business.builder()
                .id(1L).businessName("Test Store").owner(seller).build();

        wallet = Wallet.builder()
                .id(1L).business(business)
                .balance(new BigDecimal("50000"))
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

    // =====================
    // WITHDRAW
    // =====================

    @Test
    void withdraw_success_deductsWalletAndCreatesWithdrawal() {
        doNothing().when(pinService).verifyPin("1234");

        WithdrawalTransactionService.WithdrawalInitData initData =
                new WithdrawalTransactionService.WithdrawalInitData("WD-REF123", "RCP_test123");

        when(withdrawalTransactionService.initializeWithdrawal(any(WithdrawalRequest.class)))
                .thenReturn(initData);
        when(paystackService.initiateTransfer(
                eq("RCP_test123"), eq(new BigDecimal("10000")),
                eq("WD-REF123"), eq("Vendor Withdrawal")))
                .thenReturn(Map.of("transfer_code", "TRF_test123"));

        Withdrawal savedWithdrawal = Withdrawal.builder()
                .id(1L).business(business)
                .amount(new BigDecimal("10000"))
                .reference("WD-REF123")
                .transferCode("TRF_test123")
                .status(WithdrawalStatus.PENDING)
                .createdAt(LocalDateTime.now()).build();

        when(withdrawalTransactionService.completeWithdrawal("WD-REF123", "TRF_test123"))
                .thenReturn(savedWithdrawal);

        WithdrawalResponse response = withdrawalService.withdraw(request);

        assertNotNull(response);
        assertEquals(0, new BigDecimal("10000").compareTo(response.getAmount()));
        assertEquals("WD-REF123", response.getReference());
        assertEquals("TRF_test123", response.getTransferCode());

        verify(withdrawalTransactionService).initializeWithdrawal(request);
        verify(paystackService).initiateTransfer(
                eq("RCP_test123"), eq(new BigDecimal("10000")),
                eq("WD-REF123"), eq("Vendor Withdrawal"));
        verify(withdrawalTransactionService).completeWithdrawal("WD-REF123", "TRF_test123");
    }

    @Test
    void withdraw_insufficientBalance_throws() {
        doNothing().when(pinService).verifyPin("1234");

        when(withdrawalTransactionService.initializeWithdrawal(any(WithdrawalRequest.class)))
                .thenThrow(new BusinessException("Insufficient available balance"));

        assertThrows(BusinessException.class,
                () -> withdrawalService.withdraw(request));

        verify(paystackService, never()).initiateTransfer(any(), any(), any(), any());
    }

    @Test
    void withdraw_unauthorizedBankAccount_throws() {
        doNothing().when(pinService).verifyPin("1234");

        when(withdrawalTransactionService.initializeWithdrawal(any(WithdrawalRequest.class)))
                .thenThrow(new BusinessException("Unauthorized bank account"));

        assertThrows(BusinessException.class,
                () -> withdrawalService.withdraw(request));

        verify(paystackService, never()).initiateTransfer(any(), any(), any(), any());
    }

    @Test
    void withdraw_paystackNetworkFailure_setsFailureReason() {
        doNothing().when(pinService).verifyPin("1234");

        WithdrawalTransactionService.WithdrawalInitData initData =
                new WithdrawalTransactionService.WithdrawalInitData("WD-REF123", "RCP_test123");

        when(withdrawalTransactionService.initializeWithdrawal(any(WithdrawalRequest.class)))
                .thenReturn(initData);
        when(paystackService.initiateTransfer(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Network timeout"));

        Withdrawal savedWithdrawal = Withdrawal.builder()
                .id(1L).business(business)
                .amount(new BigDecimal("10000"))
                .reference("WD-REF123")
                .status(WithdrawalStatus.PENDING)
                .failureReason("Initial network timeout: awaiting reconciliation.")
                .createdAt(LocalDateTime.now()).build();

        when(withdrawalTransactionService.completeWithdrawal(eq("WD-REF123"), isNull()))
                .thenReturn(savedWithdrawal);

        WithdrawalResponse response = withdrawalService.withdraw(request);

        assertNull(response.getTransferCode());
        assertEquals("Initial network timeout: awaiting reconciliation.",
                savedWithdrawal.getFailureReason());
        verify(withdrawalTransactionService).completeWithdrawal(eq("WD-REF123"), isNull());
    }

    @Test
    void withdraw_paystackReturnsNoTransferCode_savesNull() {
        doNothing().when(pinService).verifyPin("1234");

        WithdrawalTransactionService.WithdrawalInitData initData =
                new WithdrawalTransactionService.WithdrawalInitData("WD-REF123", "RCP_test123");

        when(withdrawalTransactionService.initializeWithdrawal(any(WithdrawalRequest.class)))
                .thenReturn(initData);
        when(paystackService.initiateTransfer(any(), any(), any(), any()))
                .thenReturn(Map.of()); // no transfer_code in response

        Withdrawal savedWithdrawal = Withdrawal.builder()
                .id(1L).business(business)
                .amount(new BigDecimal("10000"))
                .reference("WD-REF123")
                .status(WithdrawalStatus.PENDING)
                .failureReason("Initial network timeout: awaiting reconciliation.")
                .createdAt(LocalDateTime.now()).build();

        when(withdrawalTransactionService.completeWithdrawal(eq("WD-REF123"), isNull()))
                .thenReturn(savedWithdrawal);

        WithdrawalResponse response = withdrawalService.withdraw(request);

        assertNull(response.getTransferCode());
        assertEquals("Initial network timeout: awaiting reconciliation.",
                savedWithdrawal.getFailureReason());
        verify(withdrawalTransactionService).completeWithdrawal(eq("WD-REF123"), isNull());
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
        wallet.setBalance(new BigDecimal("40000")); // after initial deduction

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
                .compareTo(wallet.getBalance()));
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
