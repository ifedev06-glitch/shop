package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.SaleRequest;
import com.example.shopsecure.dto.response.SaleResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SaleService {

    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final ProductRepository productRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    public SaleResponse createSale(SaleRequest request) {
        User currentUser = getCurrentUser();
        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first"));

        List<SaleItem> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (SaleRequest.Item reqItem : request.getItems()) {
            Product product = productRepository.findByIdForUpdate(reqItem.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + reqItem.getProductId()));

            if (!product.getBusiness().getId().equals(business.getId())) {
                throw new BusinessException("Product does not belong to your business");
            }

            if (product.getStock() < reqItem.getQuantity()) {
                throw new BusinessException("Insufficient stock for product: " + product.getName());
            }

            BigDecimal sellingPrice = product.getPrice();
            BigDecimal costPrice = product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;
            BigDecimal subtotal = sellingPrice.multiply(BigDecimal.valueOf(reqItem.getQuantity()));
            totalAmount = totalAmount.add(subtotal);

            SaleItem item = SaleItem.builder()
                    .product(product)
                    .productName(product.getName())
                    .quantity(reqItem.getQuantity())
                    .sellingPrice(sellingPrice)
                    .costPrice(costPrice)
                    .subtotal(subtotal)
                    .build();
            items.add(item);

            product.setStock(product.getStock() - reqItem.getQuantity());
            productRepository.save(product);
        }

        BigDecimal discount = request.getDiscount() != null ? request.getDiscount() : BigDecimal.ZERO;
        BigDecimal finalAmount = totalAmount.subtract(discount);
        if (finalAmount.compareTo(BigDecimal.ZERO) < 0) {
            finalAmount = BigDecimal.ZERO;
        }

        String saleNumber = "SALE-" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 12);

        Sale sale = Sale.builder()
                .saleNumber(saleNumber)
                .business(business)
                .totalAmount(finalAmount)
                .discount(discount)
                .paymentMethod(request.getPaymentMethod())
                .customerName(request.getCustomerName())
                .customerPhone(request.getCustomerPhone())
                .notes(request.getNotes())
                .createdAt(LocalDateTime.now())
                .items(items)
                .build();

        items.forEach(item -> item.setSale(sale));

        Sale saved = saleRepository.save(sale);
        log.info("Sale created: saleNumber={}, businessId={}, amount={}",
                saleNumber, business.getId(), finalAmount);
        return toResponse(saved);
    }

    public List<SaleResponse> getMySales() {
        User currentUser = getCurrentUser();
        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first"));

        return saleRepository.findByBusiness_IdOrderByCreatedAtDesc(business.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public Page<SaleResponse> getMySalesPaged(int page, int size) {
        User currentUser = getCurrentUser();
        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first"));

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return saleRepository.findByBusiness_IdOrderByCreatedAtDesc(business.getId(), pageable)
                .map(this::toResponse);
    }

    public SaleResponse getSaleById(Long saleId) {
        User currentUser = getCurrentUser();
        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first"));

        Sale sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found"));

        if (!sale.getBusiness().getId().equals(business.getId())) {
            throw new ResourceNotFoundException("Sale not found");
        }

        return toResponse(sale);
    }

    public void deleteSale(Long saleId) {
        User currentUser = getCurrentUser();
        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first"));

        Sale sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found"));

        if (!sale.getBusiness().getId().equals(business.getId())) {
            throw new ResourceNotFoundException("Sale not found");
        }

        for (SaleItem item : sale.getItems()) {
            Product product = productRepository.findByIdForUpdate(item.getProduct().getId())
                    .orElse(null);
            if (product != null) {
                product.setStock(product.getStock() + item.getQuantity());
                productRepository.save(product);
            }
        }

        saleRepository.delete(sale);
        log.info("Sale deleted and stock restored: saleId={}", saleId);
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private SaleResponse toResponse(Sale sale) {
        List<SaleResponse.ItemResponse> itemResponses = sale.getItems().stream()
                .map(item -> SaleResponse.ItemResponse.builder()
                        .id(item.getId())
                        .productId(item.getProduct().getId())
                        .productName(item.getProductName())
                        .quantity(item.getQuantity())
                        .sellingPrice(item.getSellingPrice())
                        .costPrice(item.getCostPrice())
                        .subtotal(item.getSubtotal())
                        .build())
                .toList();

        return SaleResponse.builder()
                .id(sale.getId())
                .saleNumber(sale.getSaleNumber())
                .totalAmount(sale.getTotalAmount())
                .discount(sale.getDiscount())
                .paymentMethod(sale.getPaymentMethod())
                .customerName(sale.getCustomerName())
                .customerPhone(sale.getCustomerPhone())
                .notes(sale.getNotes())
                .createdAt(sale.getCreatedAt())
                .items(itemResponses)
                .build();
    }
}
