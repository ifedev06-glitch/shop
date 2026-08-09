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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;

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
    public IssuedOtp issue(OtpPurpose purpose, String subject, String phoneNumber) {
        return issue(purpose, subject, phoneNumber, null);
    }

    @Transactional
    public IssuedOtp issueEmail(OtpPurpose purpose, String subject, String email) {
        return issue(purpose, subject, null, email);
    }

    private IssuedOtp issue(OtpPurpose purpose, String subject, String phoneNumber, String email) {
        LocalDateTime now = LocalDateTime.now();
        otpChallengeRepository.findTopByPurposeAndSubjectOrderByCreatedAtDesc(purpose, subject)
                .filter(challenge -> challenge.getConsumedAt() == null)
                .filter(challenge -> challenge.getCreatedAt().plus(RESEND_COOLDOWN).isAfter(now))
                .ifPresent(challenge -> {
                    throw new BusinessException("Please wait before requesting another verification code");
                });

        otpChallengeRepository
                .findByPurposeAndSubjectAndConsumedAtIsNullAndInvalidatedAtIsNull(purpose, subject)
                .forEach(challenge -> {
                    challenge.setInvalidatedAt(now);
                    otpChallengeRepository.save(challenge);
                });

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        String publicId = randomToken();
        OtpChallenge challenge = otpChallengeRepository.save(OtpChallenge.builder()
                .publicId(publicId)
                .purpose(purpose)
                .subject(subject)
                .phoneNumber(phoneNumber)
                .email(email)
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(now.plus(CODE_LIFETIME))
                .attempts(0)
                .createdAt(now)
                .build());
        return new IssuedOtp(challenge.getPublicId(), code, challenge.getExpiresAt());
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public void verify(OtpPurpose purpose, String subject, String publicId, String code) {
        OtpChallenge challenge = otpChallengeRepository
                .findByPublicIdAndPurposeAndSubject(publicId, purpose, subject)
                .orElseThrow(() -> new OtpVerificationException("Invalid or expired verification code"));

        verify(challenge, code);
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public void verifyLatest(OtpPurpose purpose, String subject, String code) {
        OtpChallenge challenge = otpChallengeRepository
                .findFirstByPurposeAndSubjectOrderByCreatedAtDesc(purpose, subject)
                .orElseThrow(() -> new OtpVerificationException("Invalid or expired verification code"));

        verify(challenge, code);
    }

    private void verify(OtpChallenge challenge, String code) {
        LocalDateTime now = LocalDateTime.now();
        if (challenge.getConsumedAt() != null
                || challenge.getInvalidatedAt() != null
                || !challenge.getExpiresAt().isAfter(now)) {
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

    @Transactional(readOnly = true, noRollbackFor = OtpVerificationException.class)
    public OtpChallenge requireChallenge(String publicId, OtpPurpose purpose, String subject) {
        return otpChallengeRepository.findByPublicId(publicId)
                .filter(challenge -> challenge.getPurpose() == purpose)
                .filter(challenge -> challenge.getSubject().equals(subject))
                .filter(challenge -> challenge.getInvalidatedAt() == null)
                .orElseThrow(() -> new OtpVerificationException("Invalid verification challenge"));
    }

    private String randomToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record IssuedOtp(String challengeId, String code, LocalDateTime expiresAt) {
        public long expiresInSeconds() {
            return Math.max(0, Duration.between(LocalDateTime.now(), expiresAt).toSeconds());
        }
    }
}
