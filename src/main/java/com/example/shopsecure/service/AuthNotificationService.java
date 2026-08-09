package com.example.shopsecure.service;

import com.example.shopsecure.entity.OtpPurpose;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthNotificationService {

    private final OtpDeliveryService otpDeliveryService;

    @Async
    public void sendEmailCode(String email, String code, OtpPurpose purpose) {
        try {
            otpDeliveryService.sendEmail(email, code, purpose);
        } catch (RuntimeException exception) {
            log.error("Unable to send authentication verification email: purpose={}", purpose, exception);
        }
    }
}
