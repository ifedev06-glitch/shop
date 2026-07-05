package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.WalletResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.BusinessRepository;
import com.example.shopsecure.repository.UserRepository;
import com.example.shopsecure.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class WalletService {

    private final WalletRepository walletRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    public WalletResponse getWallet() {
        User currentUser = getCurrentUser();
        Business business = businessRepository
                .findByOwner_Id(currentUser.getId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Business not found"));
        Wallet wallet = walletRepository
                .findByBusiness_Id(business.getId())
                .orElseGet(() -> createWallet(business));
        return mapToResponse(wallet);
    }

    public WalletResponse credit(Long businessId, BigDecimal amount) {
        Wallet wallet = walletRepository
                .findByBusiness_IdForUpdate(businessId)
                .orElseGet(() -> {
                    Business business = businessRepository
                            .findById(businessId)
                            .orElseThrow(() ->
                                    new ResourceNotFoundException("Business not found"));
                    return createWallet(business);
                });

        wallet.setBalance(wallet.getBalance().add(amount));
        wallet.setUpdatedAt(LocalDateTime.now());

        Wallet saved = walletRepository.save(wallet);
        log.info("Wallet credited: businessId={}, amount={}, newBalance={}",
                businessId, amount, saved.getBalance());
        return mapToResponse(saved);
    }

    public WalletResponse debit(Long businessId, BigDecimal amount) {
        Wallet wallet = walletRepository
                .findByBusiness_IdForUpdate(businessId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Wallet not found"));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new RuntimeException("Insufficient balance");
        }

        wallet.setBalance(wallet.getBalance().subtract(amount));
        wallet.setUpdatedAt(LocalDateTime.now());

        Wallet saved = walletRepository.save(wallet);
        log.info("Wallet debited: businessId={}, amount={}, newBalance={}",
                businessId, amount, saved.getBalance());
        return mapToResponse(saved);
    }

    private Wallet createWallet(Business business) {
        Wallet wallet = Wallet.builder()
                .business(business)
                .balance(BigDecimal.ZERO)
                .totalWithdrawn(BigDecimal.ZERO)
                .status(WalletStatus.ACTIVE)
                .currency("NGN")
                .createdAt(LocalDateTime.now())
                .build();
        return walletRepository.save(wallet);
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new ResourceNotFoundException("User not found"));
    }

    private WalletResponse mapToResponse(Wallet wallet) {
        return WalletResponse.builder()
                .walletId(wallet.getId())
                .businessName(wallet.getBusiness().getBusinessName())
                .balance(wallet.getBalance())
                .build();
    }
}
