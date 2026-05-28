package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.CreateBusinessRequest;
import com.example.shopsecure.dto.response.BusinessResponse;
import com.example.shopsecure.entity.Business;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.repository.BusinessRepository;
import com.example.shopsecure.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class BusinessService {

    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    @Value("${app.base-url}")
    private String baseUrl;

    public BusinessResponse createBusiness(CreateBusinessRequest request) {
        User currentUser = getCurrentUser();
        if (businessRepository.findByOwner_Id(currentUser.getId()).isPresent()) {
            throw new RuntimeException("You already have a business");}
        String slug = generateSlug(request.getBusinessName());
        Business business = Business.builder()
                .businessName(request.getBusinessName())
                .storefrontSlug(slug)
                .description(request.getDescription())
                .phoneNumber(request.getPhoneNumber())
                .address(request.getAddress())
                .owner(currentUser)
                .createdAt(LocalDateTime.now())
                .build();
        Business saved = businessRepository.save(business);
        return toResponse(saved);
    }

    public BusinessResponse getBusiness() {
        User currentUser = getCurrentUser();
        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new RuntimeException("No business found"));
        return toResponse(business);
    }
    private String generateSlug(String businessName) {
        // 1. Clean the business name into lowercase with hyphens
        String base = businessName
                .toLowerCase()
                .trim()
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", "-");

        // 2. Generate a universally unique 8-character string
        String shortUuid = java.util.UUID.randomUUID().toString().substring(0, 8);

        // 3. Combine them (e.g., "my-awesome-shop-a1b2c3d4")
        return base + "-" + shortUuid;
    }


    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private BusinessResponse toResponse(Business business) {
        return BusinessResponse.builder()
                .id(business.getId())
                .businessName(business.getBusinessName())
                .storefrontSlug(business.getStorefrontSlug())
                .storefrontUrl(baseUrl + "/" + business.getStorefrontSlug())
                .description(business.getDescription())
                .phoneNumber(business.getPhoneNumber())
                .address(business.getAddress())
                .createdAt(business.getCreatedAt())
                .build();
    }
}