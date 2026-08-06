package com.example.shopsecure.service;

import com.example.shopsecure.entity.OtpChallenge;
import com.example.shopsecure.entity.OtpPurpose;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.OtpVerificationException;
import com.example.shopsecure.repository.OtpChallengeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OtpService {

    private static final Duration CODE_LIFETIME = Duration.ofMinutes(5);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 5;

    private final OtpChallengeRepository otpChallengeRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public String issue(OtpPurpose purpose, String subject, String phoneNumber) {
        LocalDateTime now = LocalDateTime.now();
        otpChallengeRepository.findTopByPurposeAndSubjectOrderByCreatedAtDesc(purpose, subject)
                .filter(challenge -> challenge.getConsumedAt() == null)
                .filter(challenge -> challenge.getCreatedAt().plus(RESEND_COOLDOWN).isAfter(now))
                .ifPresent(challenge -> {
                    throw new BusinessException("Please wait before requesting another verification code");
                });

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        otpChallengeRepository.save(OtpChallenge.builder()
                .purpose(purpose)
                .subject(subject)
                .phoneNumber(phoneNumber)
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(now.plus(CODE_LIFETIME))
                .attempts(0)
                .createdAt(now)
                .build());
        return code;
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public void verify(OtpPurpose purpose, String subject, String code) {
        OtpChallenge challenge = otpChallengeRepository
                .findFirstByPurposeAndSubjectOrderByCreatedAtDesc(purpose, subject)
                .orElseThrow(() -> new OtpVerificationException("Invalid or expired verification code"));

        LocalDateTime now = LocalDateTime.now();
        if (challenge.getConsumedAt() != null || !challenge.getExpiresAt().isAfter(now)) {
            throw new OtpVerificationException("Invalid or expired verification code");
        }
        if (challenge.getAttempts() >= MAX_ATTEMPTS) {
            throw new OtpVerificationException("Too many verification attempts");
        }

        if (!passwordEncoder.matches(code, challenge.getCodeHash())) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            otpChallengeRepository.save(challenge);
            throw new OtpVerificationException("Invalid or expired verification code");
        }

        challenge.setConsumedAt(now);
        otpChallengeRepository.save(challenge);
    }
}
