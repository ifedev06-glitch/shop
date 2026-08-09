package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.ResolveBankAccountRequest;
import com.example.shopsecure.dto.response.BankResponse;
import com.example.shopsecure.dto.response.ResolvedBankAccountResponse;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.provider.payment.Bank;
import com.example.shopsecure.provider.payment.BankAccountLookupRequest;
import com.example.shopsecure.provider.payment.PayoutProvider;
import com.example.shopsecure.provider.payment.ResolvedBankAccount;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class BankAccountService {

    private final PayoutProvider payoutProvider;
    private volatile List<Bank> cachedBanks = List.of();
    private volatile Instant banksExpireAt = Instant.EPOCH;
    private final Cache<BankAccountLookupRequest, ResolvedBankAccount> resolvedAccounts =
            Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofMinutes(10))
                    .maximumSize(10_000)
                    .build();

    public List<BankResponse> listBanks() {
        return banks().stream()
                .map(bank -> new BankResponse(bank.name(), bank.code()))
                .toList();
    }

    public ResolvedBankAccountResponse resolve(ResolveBankAccountRequest request) {
        ResolvedBankAccount resolved = resolve(request.accountNumber(), request.bankCode());
        return new ResolvedBankAccountResponse(
                maskAccount(resolved.accountNumber()),
                resolved.accountName(),
                resolved.bankCode(),
                resolved.bankName()
        );
    }

    public ResolvedBankAccount resolve(String accountNumber, String bankCode) {
        Bank bank = banks().stream()
                .filter(candidate -> candidate.code().equals(bankCode))
                .findFirst()
                .orElseThrow(() -> new BusinessException("Selected bank is not supported"));
        BankAccountLookupRequest lookup = new BankAccountLookupRequest(accountNumber, bankCode);
        ResolvedBankAccount resolved = resolvedAccounts.get(lookup, payoutProvider::resolveBankAccount);
        return new ResolvedBankAccount(
                resolved.accountName(),
                resolved.accountNumber(),
                resolved.bankCode(),
                resolved.bankName() == null || resolved.bankName().isBlank()
                        ? bank.name()
                        : resolved.bankName()
        );
    }

    private synchronized List<Bank> banks() {
        if (banksExpireAt.isAfter(Instant.now()) && !cachedBanks.isEmpty()) {
            return cachedBanks;
        }
        cachedBanks = List.copyOf(payoutProvider.listBanks());
        banksExpireAt = Instant.now().plus(Duration.ofHours(1));
        return cachedBanks;
    }

    private String maskAccount(String accountNumber) {
        return "******" + accountNumber.substring(accountNumber.length() - 4);
    }
}
