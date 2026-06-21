package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.WalletResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.BusinessRepository;
import com.example.shopsecure.repository.OrderRepository;
import com.example.shopsecure.repository.UserRepository;
import com.example.shopsecure.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class WalletService {

    private final WalletRepository walletRepository;
    private final OrderRepository orderRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    public WalletResponse getWallet() {
        User currentUser = getCurrentUser();
        Business business = businessRepository
                .findByOwner_Id(currentUser.getId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Business not found")); // ← changed
        Wallet wallet = walletRepository
                .findByBusiness_Id(business.getId())
                .orElseGet(() -> createWallet(business));
        return mapToResponse(wallet);
    }

    public WalletResponse addToProcessingBalance(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found")); // ← changed

        Business business = order.getBusiness();

        Wallet wallet = walletRepository
                .findByBusiness_IdForUpdate(business.getId())
                .orElseGet(() -> createWallet(business));

        BigDecimal amount = order.getTotalAmount();
        wallet.setProcessingBalance(
                wallet.getProcessingBalance().add(amount));

        Wallet saved = walletRepository.save(wallet);
        return mapToResponse(saved);
    }

    public WalletResponse releaseFunds(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found")); // ← changed

        if (Boolean.TRUE.equals(order.getSettled())) {
            throw new BusinessException("Funds already released"); // ← changed
        }
        if (order.getStatus() != OrderStatus.SHIPPED) {
            throw new BusinessException("Order has not been shipped yet"); // ← changed
        }

        Business business = order.getBusiness();

        Wallet wallet = walletRepository
                .findByBusiness_IdForUpdate(business.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found")); // ← changed

        BigDecimal amount = order.getTotalAmount();
        if (wallet.getProcessingBalance().compareTo(amount) < 0) {
            throw new BusinessException("Insufficient processing balance"); // ← changed
        }

        wallet.setProcessingBalance(
                wallet.getProcessingBalance().subtract(amount));
        wallet.setAvailableBalance(
                wallet.getAvailableBalance().add(amount));

        walletRepository.save(wallet);
        order.setSettled(true);
        orderRepository.save(order);

        return mapToResponse(wallet);
    }

    private Wallet createWallet(Business business) {
        Wallet wallet = Wallet.builder()
                .business(business)
                .availableBalance(BigDecimal.ZERO)
                .processingBalance(BigDecimal.ZERO)
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
                        new ResourceNotFoundException("User not found")); // ← changed
    }

    private WalletResponse mapToResponse(Wallet wallet) {
        return WalletResponse.builder()
                .walletId(wallet.getId())
                .businessName(wallet.getBusiness().getBusinessName())
                .availableBalance(wallet.getAvailableBalance())
                .processingBalance(wallet.getProcessingBalance())
                .build();
    }
}