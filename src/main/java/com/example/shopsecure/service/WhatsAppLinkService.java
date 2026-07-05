package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.WhatsAppLinkResponse;
import com.example.shopsecure.entity.Business;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.entity.WhatsAppLink;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.BusinessRepository;
import com.example.shopsecure.repository.UserRepository;
import com.example.shopsecure.repository.WhatsAppLinkRepository;
import com.example.shopsecure.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class WhatsAppLinkService {

    private final WhatsAppLinkRepository whatsAppLinkRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RestTemplate restTemplate;

    @Value("${whatsapp.bot.url}")
    private String botUrl;

    public WhatsAppLinkResponse linkWhatsApp(String phoneNumber) {
        Business business = getCurrentBusiness();

        whatsAppLinkRepository.findByPhoneNumber(phoneNumber)
                .filter(link -> !link.getBusiness().getId().equals(business.getId()))
                .ifPresent(link -> {
                    throw new BusinessException("This WhatsApp number is already linked to another account.");
                });

        WhatsAppLink link = whatsAppLinkRepository
                .findByBusiness_Id(business.getId())
                .orElse(WhatsAppLink.builder()
                        .business(business)
                        .createdAt(LocalDateTime.now())
                        .build());

        link.setPhoneNumber(phoneNumber);
        whatsAppLinkRepository.save(link);

        log.info("WhatsApp linked for businessId={} phone={}", business.getId(), phoneNumber);

        // Notify the bot to send a welcome message
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, String> body = Map.of(
                    "phone_number", phoneNumber,
                    "business_name", business.getBusinessName()
            );
            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(botUrl + "/send-welcome", request, String.class);
            log.info("Welcome message sent to bot for businessId={}", business.getId());
        } catch (Exception e) {
            log.warn("Failed to send welcome message via bot for businessId={}: {}", business.getId(), e.getMessage());
        }

        return WhatsAppLinkResponse.builder()
                .phoneNumber(phoneNumber)
                .build();
    }

    public WhatsAppLinkResponse getLinkedWhatsApp() {
        Business business = getCurrentBusiness();
        return whatsAppLinkRepository.findByBusiness_Id(business.getId())
                .map(link -> WhatsAppLinkResponse.builder()
                        .phoneNumber(link.getPhoneNumber())
                        .build())
                .orElse(null);
    }

    public void unlinkWhatsApp() {
        Business business = getCurrentBusiness();
        whatsAppLinkRepository.findByBusiness_Id(business.getId())
                .ifPresent(link -> {
                    whatsAppLinkRepository.delete(link);
                    log.info("WhatsApp unlinked for businessId={}", business.getId());
                });
    }

    public Map<String, String> getTokenByPhone(String phoneNumber) {
        WhatsAppLink link = whatsAppLinkRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new ResourceNotFoundException("WhatsApp number not linked"));

        User user = link.getBusiness().getOwner();
        String token = jwtService.generateToken(user);

        log.info("Token issued for WhatsApp phone={} businessId={}", phoneNumber, link.getBusiness().getId());
        return Map.of("token", token);
    }

    private Business getCurrentBusiness() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return businessRepository.findByOwner_Id(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
    }
}
