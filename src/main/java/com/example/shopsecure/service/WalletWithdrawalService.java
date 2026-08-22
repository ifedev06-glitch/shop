package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.CreateWalletWithdrawalRequest;
import com.example.shopsecure.dto.request.ResolveBankAccountRequest;
import com.example.shopsecure.dto.request.WalletWithdrawalChallengeRequest;
import com.example.shopsecure.dto.response.WalletWithdrawalResponse;
import com.example.shopsecure.dto.response.WalletWithdrawalChallengeResponse;
import com.example.shopsecure.dto.response.WithdrawalBankAccountResponse;
import com.example.shopsecure.dto.response.PageResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.OtpVerificationException;
import com.example.shopsecure.exception.PayoutRejectedException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.provider.payment.*;
import com.example.shopsecure.repository.*;
import com.example.shopsecure.util.Pagination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WalletWithdrawalService {

    private final OrderService orderService;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;
    private final WalletWithdrawalRepository withdrawalRepository;
    private final OrderSettlementRepository settlementRepository;
    private final WithdrawalBankAccountRepository bankAccountRepository;
    private final BankAccountService bankAccountService;
    private final SensitiveDataCipher dataCipher;
    private final PayoutProvider payoutProvider;
    private final OtpService otpService;
    private final OtpDeliveryService otpDeliveryService;
    private final PlatformTransactionManager transactionManager;

    @Value("${wallet.withdrawal.reconciliation.batch-size:50}")
    private int reconciliationBatchSize;

    @Value("${wallet.withdrawal.max-dispatch-attempts:3}")
    private int maxDispatchAttempts;

    public WithdrawalBankAccountResponse saveBankAccount(ResolveBankAccountRequest request) {
        User current = orderService.currentUser();
        ResolvedBankAccount resolved = bankAccountService.resolve(
                request.accountNumber(),
                request.bankCode()
        );
        PayoutRecipientResponse recipient = payoutProvider.createPayoutRecipient(
                new PayoutRecipientRequest(
                        resolved.accountName(),
                        resolved.accountNumber(),
                        resolved.bankCode()
                )
        );

        return transaction().execute(status -> {
            User user = userRepository.findByIdForUpdate(current.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
            WithdrawalBankAccount account = bankAccountRepository.findByUserIdForUpdate(user.getId())
                    .orElseGet(() -> WithdrawalBankAccount.builder()
                            .user(user)
                            .createdAt(Instant.now())
                            .build());
            String accountNumber = resolved.accountNumber();
            account.setAccountNumberEncrypted(dataCipher.encrypt(accountNumber));
            account.setAccountNumberLast4(accountNumber.substring(accountNumber.length() - 4));
            account.setBankCode(resolved.bankCode());
            account.setBankName(resolved.bankName());
            account.setAccountName(resolved.accountName());
            account.setPayoutRecipientCode(recipient.recipientId());
            account.setUpdatedAt(Instant.now());
            return bankAccountResponse(bankAccountRepository.save(account));
        });
    }

    @Transactional(readOnly = true)
    public WithdrawalBankAccountResponse getBankAccount() {
        User user = orderService.currentUser();
        return bankAccountRepository.findByUser_Id(user.getId())
                .map(this::bankAccountResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Withdrawal bank account was not found"));
    }

    @Transactional
    public WalletWithdrawalChallengeResponse requestWithdrawalChallenge(
            WalletWithdrawalChallengeRequest request
    ) {
        User user = orderService.currentUser();
        BigDecimal amount = money(request.amount());
        Wallet wallet = walletRepository.findByUser_Id(user.getId())
                .orElseThrow(() -> new BusinessException("Wallet was not found"));
        if (walletBalance(wallet.getBalance()).compareTo(amount) < 0) {
            throw new BusinessException("Insufficient available wallet balance");
        }
        WithdrawalBankAccount bank = bankAccountRepository.findByUser_Id(user.getId())
                .orElseThrow(() -> new BusinessException("Add a verified withdrawal bank account first"));
        if (user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) {
            throw new BusinessException("Account phone number is required for withdrawal verification");
        }
        OtpService.IssuedOtp issued = otpService.issue(
                OtpPurpose.WALLET_WITHDRAWAL,
                withdrawalSubject(user.getId(), amount, bank),
                user.getPhoneNumber()
        );
        otpDeliveryService.send(
                user.getPhoneNumber(),
                user.getEmail(),
                issued.code(),
                OtpPurpose.WALLET_WITHDRAWAL
        );
        return new WalletWithdrawalChallengeResponse(
                issued.challengeId(),
                maskPhone(user.getPhoneNumber()),
                issued.expiresInSeconds()
        );
    }

    public WalletWithdrawalResponse createWithdrawal(
            CreateWalletWithdrawalRequest request,
            String idempotencyKey
    ) {
        validateIdempotencyKey(idempotencyKey);
        BigDecimal amount = money(request.amount());
        User current = orderService.currentUser();
        PreparationResult preparation = transaction().execute(status ->
                prepareWithdrawal(
                        current.getId(),
                        amount,
                        idempotencyKey,
                        request.challengeId(),
                        request.code()
                )
        );
        if (preparation == null) {
            throw new IllegalStateException("Wallet withdrawal was not prepared");
        }
        if (preparation.verificationFailure() != null) {
            throw preparation.verificationFailure();
        }
        PreparedWithdrawal prepared = preparation.withdrawal();
        if (prepared == null || !prepared.dispatch()) {
            return prepared == null ? null : prepared.response();
        }

        try {
            PayoutResponse providerResponse = payoutProvider.initiatePayout(new PayoutRequest(
                    prepared.recipientCode(),
                    prepared.amount(),
                    prepared.reference(),
                    "ShopSecure wallet withdrawal"
            ));
            if (!prepared.reference().equals(providerResponse.reference())) {
                return transaction().execute(status -> markRequiresAction(
                        prepared.withdrawalId(),
                        "Provider returned a mismatched withdrawal reference"
                ));
            }
            return transaction().execute(status -> applyInitiation(prepared.withdrawalId(), providerResponse));
        } catch (PayoutRejectedException exception) {
            return transaction().execute(status -> markRejected(prepared.withdrawalId()));
        } catch (RuntimeException exception) {
            return transaction().execute(status -> markDispatchUnknown(prepared.withdrawalId()));
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<WalletWithdrawalResponse> getWithdrawals(int page, int size) {
        User user = orderService.currentUser();
        return PageResponse.from(withdrawalRepository.findByWallet_User_Id(
                user.getId(),
                Pagination.newestFirst(page, size)
        ).map(this::response));
    }

    @Transactional
    public boolean applyPayoutStatus(PayoutStatusUpdatedEvent event) {
        if (!event.relevant()) {
            return false;
        }
        java.util.Optional<WalletWithdrawal> byReference = event.reference() == null
                ? java.util.Optional.empty()
                : withdrawalRepository.findByReference(event.reference());
        java.util.Optional<WalletWithdrawal> byTransfer = event.transferId() == null
                || event.transferId().isBlank()
                ? java.util.Optional.empty()
                : withdrawalRepository.findByTransferId(event.transferId());
        java.util.Optional<OrderSettlement> settlementByReference = event.reference() == null
                ? java.util.Optional.empty()
                : settlementRepository.findByPayoutReference(event.reference());
        java.util.Optional<OrderSettlement> settlementByTransfer = event.transferId() == null
                || event.transferId().isBlank()
                ? java.util.Optional.empty()
                : settlementRepository.findByTransferId(event.transferId());
        if (byTransfer.isEmpty() && settlementByTransfer.isPresent()) {
            return false;
        }
        if (byTransfer.isEmpty() && byReference.isEmpty() && settlementByReference.isPresent()) {
            return false;
        }
        WalletWithdrawal snapshot = byTransfer.or(() -> byReference).orElse(null);
        if (snapshot == null) {
            return false;
        }
        boolean crossDomainCollision = (byTransfer.isPresent() && settlementByTransfer.isPresent())
                || (byReference.isPresent() && settlementByReference.isPresent());
        WalletWithdrawal withdrawal = withdrawalRepository.findByIdForUpdate(snapshot.getId()).orElse(null);
        if (withdrawal == null) {
            return false;
        }
        if (crossDomainCollision) {
            markRequiresAction(withdrawal, "Provider payout identity is assigned to multiple payouts");
            return false;
        }
        String mismatch = payoutEventMismatch(withdrawal, event);
        if (mismatch != null) {
            markRequiresAction(withdrawal, mismatch);
            return true;
        }
        if (event.occurredAt() != null
                && withdrawal.getProviderUpdatedAt() != null
                && event.occurredAt().isBefore(withdrawal.getProviderUpdatedAt())) {
            return true;
        }
        setTransferId(withdrawal, event.transferId());
        applyState(withdrawal, event.status());
        Instant updatedAt = event.occurredAt() == null ? Instant.now() : event.occurredAt();
        withdrawal.setProviderUpdatedAt(updatedAt);
        withdrawal.setUpdatedAt(updatedAt);
        withdrawalRepository.save(withdrawal);
        return true;
    }

    @Scheduled(
            initialDelayString = "${wallet.withdrawal.reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${wallet.withdrawal.reconciliation.delay-ms:60000}"
    )
    public void reconcileWithdrawals() {
        withdrawalRepository.findByStatusIn(
                List.of(
                        WalletWithdrawalStatus.PENDING,
                        WalletWithdrawalStatus.DISPATCH_UNKNOWN,
                        WalletWithdrawalStatus.REQUIRES_ACTION
                ),
                PageRequest.of(0, Math.max(1, reconciliationBatchSize))
        ).forEach(withdrawal -> {
            try {
                reconcileWithdrawal(withdrawal.getId(), withdrawal.getReference());
            } catch (RuntimeException exception) {
                log.error("Unable to reconcile wallet withdrawal: withdrawalId={}", withdrawal.getId());
            }
        });
    }

    private PreparationResult prepareWithdrawal(
            Long userId,
            BigDecimal amount,
            String idempotencyKey,
            String challengeId,
            String code
    ) {
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException("Wallet was not found"));
        WalletWithdrawal existing = withdrawalRepository
                .findByWallet_IdAndIdempotencyKey(wallet.getId(), idempotencyKey)
                .orElse(null);
        if (existing != null) {
            if (existing.getAmount().compareTo(amount) != 0) {
                throw new BusinessException("Idempotency key was already used for a different withdrawal amount");
            }
            return PreparationResult.success(new PreparedWithdrawal(
                    existing.getId(),
                    existing.getPayoutRecipientCode(),
                    existing.getAmount(),
                    existing.getReference(),
                    false,
                    response(existing)
            ));
        }
        if (!"NGN".equalsIgnoreCase(wallet.getCurrency())) {
            throw new BusinessException("Only NGN wallet withdrawals are supported");
        }
        BigDecimal before = walletBalance(wallet.getBalance());
        if (before.compareTo(amount) < 0) {
            throw new BusinessException("Insufficient available wallet balance");
        }
        WithdrawalBankAccount bank = bankAccountRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException("Add a verified withdrawal bank account first"));
        try {
            otpService.verify(
                    OtpPurpose.WALLET_WITHDRAWAL,
                    withdrawalSubject(userId, amount, bank),
                    challengeId,
                    code
            );
        } catch (OtpVerificationException exception) {
            return PreparationResult.verificationFailure(exception);
        }

        Instant now = Instant.now();
        BigDecimal after = walletBalance(before.subtract(amount));
        wallet.setBalance(after);
        wallet.setUpdatedAt(now);
        walletRepository.save(wallet);

        WalletWithdrawal withdrawal = withdrawalRepository.save(WalletWithdrawal.builder()
                .wallet(wallet)
                .amount(amount)
                .currency(wallet.getCurrency().toUpperCase())
                .status(WalletWithdrawalStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .reference(withdrawalReference())
                .payoutRecipientCode(bank.getPayoutRecipientCode())
                .accountNumberLast4(bank.getAccountNumberLast4())
                .bankName(bank.getBankName())
                .accountName(bank.getAccountName())
                .dispatchAttempt(1)
                .createdAt(now)
                .updatedAt(now)
                .build());
        transactionRepository.save(WalletTransaction.builder()
                .wallet(wallet)
                .withdrawal(withdrawal)
                .transactionType(WalletTransactionType.WITHDRAWAL_DEBIT)
                .reference("withdrawal-debit-" + withdrawal.getId())
                .idempotencyKey(idempotencyKey)
                .amount(amount)
                .balanceBefore(before)
                .balanceAfter(after)
                .createdAt(now)
                .build());
        return PreparationResult.success(new PreparedWithdrawal(
                withdrawal.getId(),
                withdrawal.getPayoutRecipientCode(),
                amount,
                withdrawal.getReference(),
                true,
                response(withdrawal)
        ));
    }

    private WalletWithdrawalResponse applyInitiation(Long withdrawalId, PayoutResponse providerResponse) {
        WalletWithdrawal withdrawal = withdrawalRepository.findByIdForUpdate(withdrawalId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet withdrawal not found"));
        if (!withdrawal.getReference().equals(providerResponse.reference())) {
            markRequiresAction(withdrawal, "Provider returned a mismatched withdrawal reference");
            return response(withdrawal);
        }
        String ownershipMismatch = payoutTransferOwnershipMismatch(withdrawal, providerResponse.transferId());
        if (ownershipMismatch != null) {
            markRequiresAction(withdrawal, ownershipMismatch);
            return response(withdrawal);
        }
        String mismatch = payoutResponseMismatch(withdrawal, providerResponse);
        if (mismatch != null) {
            markRequiresAction(withdrawal, mismatch);
            return response(withdrawal);
        }
        setTransferId(withdrawal, providerResponse.transferId());
        PayoutStatus recoveredStatus = providerResponse.status() == PayoutStatus.SUCCESS
                && !hasCompletePayoutDetails(providerResponse)
                ? PayoutStatus.PENDING
                : providerResponse.status();
        applyState(withdrawal, recoveredStatus);
        withdrawal.setUpdatedAt(Instant.now());
        withdrawalRepository.save(withdrawal);
        return response(withdrawal);
    }

    private WalletWithdrawalResponse markDispatchUnknown(Long withdrawalId) {
        WalletWithdrawal withdrawal = withdrawalRepository.findByIdForUpdate(withdrawalId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet withdrawal not found"));
        if (!terminal(withdrawal.getStatus())) {
            withdrawal.setStatus(WalletWithdrawalStatus.DISPATCH_UNKNOWN);
            withdrawal.setFailureReason("Withdrawal dispatch outcome requires provider verification");
            withdrawal.setUpdatedAt(Instant.now());
            withdrawalRepository.save(withdrawal);
        }
        return response(withdrawal);
    }

    private WalletWithdrawalResponse markRejected(Long withdrawalId) {
        WalletWithdrawal withdrawal = withdrawalRepository.findByIdForUpdate(withdrawalId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet withdrawal not found"));
        applyState(withdrawal, PayoutStatus.FAILED);
        withdrawal.setUpdatedAt(Instant.now());
        withdrawalRepository.save(withdrawal);
        return response(withdrawal);
    }

    private WalletWithdrawalResponse markRequiresAction(Long withdrawalId, String reason) {
        WalletWithdrawal withdrawal = withdrawalRepository.findByIdForUpdate(withdrawalId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet withdrawal not found"));
        markRequiresAction(withdrawal, reason);
        return response(withdrawal);
    }

    private void markRequiresAction(WalletWithdrawal withdrawal, String reason) {
        withdrawal.setStatus(WalletWithdrawalStatus.REQUIRES_ACTION);
        withdrawal.setFailureReason(reason + "; manual reconciliation is required");
        withdrawal.setUpdatedAt(Instant.now());
        withdrawalRepository.save(withdrawal);
    }

    private String payoutResponseMismatch(
            WalletWithdrawal withdrawal,
            PayoutResponse response
    ) {
        if (!withdrawal.getReference().equals(response.reference())) {
            return "Provider returned a mismatched withdrawal reference";
        }
        if (withdrawal.getTransferId() != null
                && response.transferId() != null
                && !withdrawal.getTransferId().equals(response.transferId())) {
            return "Provider returned a conflicting withdrawal transfer ID";
        }
        if (response.status() != PayoutStatus.SUCCESS) {
            return null;
        }
        if (response.amount() != null && withdrawal.getAmount().compareTo(response.amount()) != 0) {
            return "Provider withdrawal amount does not match the frozen withdrawal";
        }
        if (response.currency() != null && !withdrawal.getCurrency().equalsIgnoreCase(response.currency())) {
            return "Provider withdrawal currency does not match the frozen withdrawal";
        }
        if (response.recipientId() != null
                && !withdrawal.getPayoutRecipientCode().equals(response.recipientId())) {
            return "Provider withdrawal recipient does not match the frozen withdrawal";
        }
        return null;
    }

    private String payoutEventMismatch(
            WalletWithdrawal withdrawal,
            PayoutStatusUpdatedEvent event
    ) {
        if (!withdrawal.getReference().equals(event.reference())) {
            return "Provider webhook returned a mismatched withdrawal reference";
        }
        if (withdrawal.getTransferId() != null
                && event.transferId() != null
                && !withdrawal.getTransferId().equals(event.transferId())) {
            return "Provider returned a conflicting withdrawal transfer ID";
        }
        if (event.status() != PayoutStatus.SUCCESS
                || withdrawal.getStatus() == WalletWithdrawalStatus.COMPLETED) {
            return null;
        }
        if (event.amount() == null || withdrawal.getAmount().compareTo(event.amount()) != 0) {
            return "Provider withdrawal amount does not match the frozen withdrawal";
        }
        if (event.currency() == null || !withdrawal.getCurrency().equalsIgnoreCase(event.currency())) {
            return "Provider withdrawal currency does not match the frozen withdrawal";
        }
        if (event.recipientId() == null
                || !withdrawal.getPayoutRecipientCode().equals(event.recipientId())) {
            return "Provider withdrawal recipient does not match the frozen withdrawal";
        }
        return null;
    }

    private boolean hasCompletePayoutDetails(PayoutResponse response) {
        return response.amount() != null
                && response.currency() != null
                && !response.currency().isBlank()
                && response.recipientId() != null
                && !response.recipientId().isBlank();
    }

    private String payoutTransferOwnershipMismatch(
            WalletWithdrawal withdrawal,
            String transferId
    ) {
        if (transferId == null || transferId.isBlank()) {
            return null;
        }
        boolean ownedByAnotherWithdrawal = withdrawalRepository.findByTransferId(transferId)
                .map(owner -> !owner.getId().equals(withdrawal.getId()))
                .orElse(false);
        if (ownedByAnotherWithdrawal || settlementRepository.findByTransferId(transferId).isPresent()) {
            return "Provider transfer ID is already assigned to another payout";
        }
        return null;
    }

    private void applyState(WalletWithdrawal withdrawal, PayoutStatus status) {
        switch (status) {
            case SUCCESS -> {
                if (terminal(withdrawal.getStatus())) {
                    return;
                }
                Instant now = Instant.now();
                withdrawal.setStatus(WalletWithdrawalStatus.COMPLETED);
                withdrawal.setFailureReason(null);
                withdrawal.setCompletedAt(now);
            }
            case FAILED -> {
                if (withdrawal.getStatus() != WalletWithdrawalStatus.COMPLETED
                        && withdrawal.getStatus() != WalletWithdrawalStatus.REVERSED) {
                    withdrawal.setStatus(WalletWithdrawalStatus.FAILED);
                    withdrawal.setFailureReason("Withdrawal failed; funds returned to available balance");
                    refund(withdrawal);
                }
            }
            case REVERSED -> {
                if (withdrawal.getStatus() != WalletWithdrawalStatus.REVERSED) {
                    withdrawal.setStatus(WalletWithdrawalStatus.REVERSED);
                    withdrawal.setFailureReason("Withdrawal reversed; funds returned to available balance");
                    refund(withdrawal);
                }
            }
            case REQUIRES_ACTION -> {
                if (!terminal(withdrawal.getStatus())) {
                    withdrawal.setStatus(WalletWithdrawalStatus.REQUIRES_ACTION);
                    withdrawal.setFailureReason("Withdrawal requires provider review");
                }
            }
            case PENDING, UNKNOWN -> {
                if (!terminal(withdrawal.getStatus())
                        && withdrawal.getStatus() != WalletWithdrawalStatus.REQUIRES_ACTION) {
                    withdrawal.setStatus(WalletWithdrawalStatus.PENDING);
                    withdrawal.setFailureReason(null);
                }
            }
        }
    }

    private void refund(WalletWithdrawal withdrawal) {
        if (transactionRepository.findByWithdrawal_IdAndTransactionType(
                withdrawal.getId(),
                WalletTransactionType.WITHDRAWAL_REFUND
        ).isPresent()) {
            return;
        }
        Wallet wallet = walletRepository.findByIdForUpdate(withdrawal.getWallet().getId())
                .orElseThrow(() -> new BusinessException("Wallet was not found"));
        BigDecimal before = walletBalance(wallet.getBalance());
        BigDecimal after = walletBalance(before.add(withdrawal.getAmount()));
        Instant now = Instant.now();
        wallet.setBalance(after);
        wallet.setUpdatedAt(now);
        walletRepository.save(wallet);
        transactionRepository.save(WalletTransaction.builder()
                .wallet(wallet)
                .withdrawal(withdrawal)
                .transactionType(WalletTransactionType.WITHDRAWAL_REFUND)
                .reference("withdrawal-refund-" + withdrawal.getId())
                .idempotencyKey("refund-" + withdrawal.getReference())
                .amount(withdrawal.getAmount())
                .balanceBefore(before)
                .balanceAfter(after)
                .createdAt(now)
                .build());
    }

    private void reconcileWithdrawal(Long withdrawalId, String reference) {
        try {
            PayoutStatusUpdatedEvent verified = payoutProvider.verifyPayout(reference);
            if (verified != null && verified.relevant()) {
                transaction().execute(status -> applyPayoutStatus(verified));
                return;
            }
        } catch (RuntimeException ignored) {
            // A deterministic reference makes a same-reference dispatch retry safe.
        }

        PreparedWithdrawal retry = transaction().execute(status -> prepareDispatchRetry(withdrawalId));
        if (retry == null || !retry.dispatch()) {
            return;
        }
        try {
            PayoutResponse response = payoutProvider.initiatePayout(new PayoutRequest(
                    retry.recipientCode(),
                    retry.amount(),
                    retry.reference(),
                    "ShopSecure wallet withdrawal retry"
            ));
            transaction().execute(status -> applyInitiation(retry.withdrawalId(), response));
        } catch (PayoutRejectedException ignored) {
            transaction().execute(status -> markRejected(retry.withdrawalId()));
        } catch (RuntimeException ignored) {
            transaction().execute(status -> markDispatchUnknown(retry.withdrawalId()));
        }
    }

    private PreparedWithdrawal prepareDispatchRetry(Long withdrawalId) {
        WalletWithdrawal withdrawal = withdrawalRepository.findByIdForUpdate(withdrawalId).orElse(null);
        if (withdrawal == null
                || terminal(withdrawal.getStatus())
                || withdrawal.getTransferId() != null
                || withdrawal.getStatus() == WalletWithdrawalStatus.REQUIRES_ACTION) {
            return null;
        }
        if (withdrawal.getDispatchAttempt() >= maxDispatchAttempts) {
            withdrawal.setStatus(WalletWithdrawalStatus.REQUIRES_ACTION);
            withdrawal.setFailureReason("Withdrawal requires provider review after repeated dispatch attempts");
            withdrawal.setUpdatedAt(Instant.now());
            withdrawalRepository.save(withdrawal);
            return null;
        }
        withdrawal.setDispatchAttempt(withdrawal.getDispatchAttempt() + 1);
        withdrawal.setUpdatedAt(Instant.now());
        withdrawalRepository.save(withdrawal);
        return new PreparedWithdrawal(
                withdrawal.getId(),
                withdrawal.getPayoutRecipientCode(),
                withdrawal.getAmount(),
                withdrawal.getReference(),
                true,
                response(withdrawal)
        );
    }

    private void setTransferId(WalletWithdrawal withdrawal, String transferId) {
        if (transferId == null || transferId.isBlank()) {
            return;
        }
        if (withdrawal.getTransferId() != null && !withdrawal.getTransferId().equals(transferId)) {
            throw new BusinessException("Withdrawal transfer reference does not match the existing withdrawal");
        }
        withdrawal.setTransferId(transferId);
    }

    private WithdrawalBankAccountResponse bankAccountResponse(WithdrawalBankAccount account) {
        return new WithdrawalBankAccountResponse(
                "******" + account.getAccountNumberLast4(),
                account.getAccountName(),
                account.getBankCode(),
                account.getBankName(),
                account.getUpdatedAt()
        );
    }

    private WalletWithdrawalResponse response(WalletWithdrawal withdrawal) {
        return new WalletWithdrawalResponse(
                withdrawal.getId(),
                withdrawal.getReference(),
                withdrawal.getAmount(),
                withdrawal.getCurrency(),
                withdrawal.getStatus(),
                "******" + withdrawal.getAccountNumberLast4(),
                withdrawal.getAccountName(),
                withdrawal.getBankName(),
                withdrawal.getFailureReason(),
                withdrawal.getCreatedAt(),
                withdrawal.getUpdatedAt(),
                withdrawal.getCompletedAt()
        );
    }

    private boolean terminal(WalletWithdrawalStatus status) {
        return status == WalletWithdrawalStatus.COMPLETED
                || status == WalletWithdrawalStatus.FAILED
                || status == WalletWithdrawalStatus.REVERSED;
    }

    private BigDecimal money(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("Withdrawal amount must be greater than zero");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new BusinessException("Withdrawal amount must have at most 2 decimal places");
        }
    }

    private BigDecimal walletBalance(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new BusinessException("Wallet balance is invalid");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new BusinessException("Idempotency-Key is required and must not exceed 255 characters");
        }
    }

    private String withdrawalReference() {
        return "wd_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String withdrawalSubject(Long userId, BigDecimal amount, WithdrawalBankAccount bank) {
        return "wallet-withdrawal:" + userId
                + ":" + amount.toPlainString()
                + ":" + bank.getId()
                + ":" + bank.getVersion();
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 5) {
            return "***";
        }
        return phone.substring(0, Math.min(5, phone.length() - 2))
                + "*".repeat(Math.max(1, phone.length() - 7))
                + phone.substring(phone.length() - 2);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private record PreparedWithdrawal(
            Long withdrawalId,
            String recipientCode,
            BigDecimal amount,
            String reference,
            boolean dispatch,
            WalletWithdrawalResponse response
    ) {
    }

    private record PreparationResult(
            PreparedWithdrawal withdrawal,
            OtpVerificationException verificationFailure
    ) {
        private static PreparationResult success(PreparedWithdrawal withdrawal) {
            return new PreparationResult(withdrawal, null);
        }

        private static PreparationResult verificationFailure(OtpVerificationException failure) {
            return new PreparationResult(null, failure);
        }
    }
}
