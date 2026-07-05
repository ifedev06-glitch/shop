package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.CreateBusinessRequest;
import com.example.shopsecure.dto.response.BusinessResponse;
import com.example.shopsecure.entity.Business;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.BusinessRepository;
import com.example.shopsecure.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessService {

    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final CloudinaryService cloudinaryService;

    @Value("${app.base-url}")
    private String baseUrl;

    public BusinessResponse createBusiness(CreateBusinessRequest request, MultipartFile logo) {
        User currentUser = getCurrentUser();
        if (businessRepository.findByOwner_Id(currentUser.getId()).isPresent()) {
            throw new BusinessException("You already have a business");
        }

        String logoUrl = null;
        if (logo != null && !logo.isEmpty()) {
            logoUrl = cloudinaryService.upload(logo, "shopsecure/logos");
        }

        String slug = generateSlug(request.getBusinessName());
        Business business = Business.builder()
                .businessName(request.getBusinessName())
                .storefrontSlug(slug)
                .description(request.getDescription())
                .phoneNumber(request.getPhoneNumber())
                .address(request.getAddress())
                .logoUrl(logoUrl)
                .owner(currentUser)
                .createdAt(LocalDateTime.now())
                .build();
        Business saved = businessRepository.save(business);
        log.info("Business created: id={}, name={}, slug={}", saved.getId(), saved.getBusinessName(), saved.getStorefrontSlug());
        return toResponse(saved);
    }

    public BusinessResponse getBusiness() {
        User currentUser = getCurrentUser();
        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No business found")); // ← changed
        log.info("Business fetched: id={}, name={}", business.getId(), business.getBusinessName());
        return toResponse(business);
    }

    private String generateSlug(String businessName) {
        String base = businessName
                .toLowerCase()
                .trim()
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", "-");

        String shortUuid = java.util.UUID.randomUUID().toString().substring(0, 8);

        return base + "-" + shortUuid;
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found")); // ← changed
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
                .logoUrl(business.getLogoUrl())
                .createdAt(business.getCreatedAt())
                .build();
    }
}