package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.CreateBankAccountRequest;
import com.example.shopsecure.dto.response.BankAccountResponse;
import com.example.shopsecure.entity.BankAccount;
import com.example.shopsecure.entity.Business;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.BankAccountRepository;
import com.example.shopsecure.repository.BusinessRepository;
import com.example.shopsecure.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional
public class BankAccountService {

    private final BankAccountRepository bankAccountRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final PaystackService paystackService;
    private final PinService pinService;

    public BankAccountResponse saveBankAccount(CreateBankAccountRequest request) {
        pinService.verifyPin(request.getPin());

        Business business = getCurrentBusiness();

        Map<String, Object> accountData =
                paystackService.resolveAccount(
                        request.getAccountNumber(),
                        request.getBankCode()
                );

        String accountName =
                accountData.get("account_name").toString();

        Map<String, Object> recipientData =
                paystackService.createTransferRecipient(
                        accountName,
                        request.getAccountNumber(),
                        request.getBankCode()
                );

        String recipientCode =
                recipientData.get("recipient_code").toString();

        BankAccount bankAccount =
                bankAccountRepository
                        .findByBusiness_Id(business.getId())
                        .orElse(BankAccount.builder()
                                .business(business)
                                .createdAt(LocalDateTime.now())
                                .build());

        bankAccount.setAccountName(accountName);
        bankAccount.setAccountNumber(request.getAccountNumber());
        bankAccount.setBankCode(request.getBankCode());

        if (recipientData.get("details") instanceof Map details) {

            Object bankName =
                    details.get("bank_name");

            if (bankName != null) {
                bankAccount.setBankName(bankName.toString());
            }
        }

        bankAccount.setRecipientCode(recipientCode);

        BankAccount saved =
                bankAccountRepository.save(bankAccount);

        return mapToResponse(saved);
    }

    public BankAccountResponse getBankAccount() {

        Business business = getCurrentBusiness();

        return bankAccountRepository.findByBusiness_Id(business.getId())
                .map(this::mapToResponse)
                .orElse(null);
    }

    private Business getCurrentBusiness() {

        String email =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication()
                        .getName();

        User user =
                userRepository.findByEmail(email)
                        .orElseThrow(() ->
                                new ResourceNotFoundException("User not found"));

        return businessRepository
                .findByOwner_Id(user.getId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Business not found"));
    }

    private BankAccountResponse mapToResponse(
            BankAccount bankAccount
    ) {

        return BankAccountResponse.builder()
                .id(bankAccount.getId())
                .accountName(bankAccount.getAccountName())
                .accountNumber(bankAccount.getAccountNumber())
                .bankCode(bankAccount.getBankCode())
                .bankName(bankAccount.getBankName())
                .build();
    }
}