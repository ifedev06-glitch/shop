package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.TransactionResponse;
import com.example.shopsecure.entity.Business;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.BusinessRepository;
import com.example.shopsecure.repository.PaymentRepository;
import com.example.shopsecure.repository.UserRepository;
import com.example.shopsecure.repository.WithdrawalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final PaymentRepository paymentRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    public Page<TransactionResponse> getTransactionHistory(int page, int size) {

        Business business = getCurrentBusiness();

        List<TransactionResponse> payments = paymentRepository
                .findByOrder_Business_Id(business.getId())
                .stream()
                .map(p -> TransactionResponse.builder()
                        .reference(p.getReference())
                        .amount(p.getAmount())
                        .type("CREDIT")
                        .status(p.getStatus().name())
                        .description("Payment for order " + p.getOrder().getOrderNumber())
                        .createdAt(p.getCreatedAt())
                        .build())
                .toList();

        List<TransactionResponse> withdrawals = withdrawalRepository
                .findByBusiness_IdOrderByCreatedAtDesc(business.getId())
                .stream()
                .map(w -> TransactionResponse.builder()
                        .reference(w.getReference())
                        .amount(w.getAmount())
                        .type("DEBIT")
                        .status(w.getStatus().name())
                        .description("Withdrawal")
                        .createdAt(w.getCreatedAt())
                        .build())
                .toList();

        List<TransactionResponse> all = Stream.concat(payments.stream(), withdrawals.stream())
                .sorted(Comparator.comparing(TransactionResponse::getCreatedAt).reversed())
                .toList();

        Pageable pageable = PageRequest.of(page, size);
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), all.size());

        if (start > all.size()) {
            return new PageImpl<>(List.of(), pageable, all.size());
        }

        return new PageImpl<>(all.subList(start, end), pageable, all.size());
    }

    private Business getCurrentBusiness() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found")); // ← changed

        return businessRepository.findByOwner_Id(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found")); // ← changed
    }
}