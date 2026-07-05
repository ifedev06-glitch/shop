package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.ReceiptResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceiptService {

    private final SaleRepository saleRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    public ReceiptResponse generateReceipt(Long saleId) {
        User currentUser = getCurrentUser();
        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first"));

        Sale sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found"));

        if (!sale.getBusiness().getId().equals(business.getId())) {
            throw new ResourceNotFoundException("Sale not found");
        }

        String receiptNumber = "RCPT-" + UUID.randomUUID().toString()
                .replace("-", "").toUpperCase().substring(0, 12);

        List<ReceiptResponse.Item> items = sale.getItems().stream()
                .map(item -> ReceiptResponse.Item.builder()
                        .productName(item.getProductName())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getSellingPrice())
                        .total(item.getSubtotal())
                        .build())
                .toList();

        BigDecimal subtotal = items.stream()
                .map(ReceiptResponse.Item::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal discount = sale.getDiscount() != null ? sale.getDiscount() : BigDecimal.ZERO;
        BigDecimal total = sale.getTotalAmount() != null ? sale.getTotalAmount() : subtotal.subtract(discount);

        return ReceiptResponse.builder()
                .receiptNumber(receiptNumber)
                .saleNumber(sale.getSaleNumber())
                .createdAt(sale.getCreatedAt())
                .businessName(business.getBusinessName())
                .businessPhone(business.getPhoneNumber())
                .businessAddress(business.getAddress())
                .logoUrl(business.getLogoUrl())
                .customerName(sale.getCustomerName())
                .customerPhone(sale.getCustomerPhone())
                .items(items)
                .subtotal(subtotal)
                .discount(discount)
                .total(total)
                .paymentMethod(sale.getPaymentMethod())
                .build();
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
