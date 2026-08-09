package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.EmailRequest;
import com.example.shopsecure.dto.request.LoginRequest;
import com.example.shopsecure.dto.request.RegisterRequest;
import com.example.shopsecure.dto.request.ResetPasswordRequest;
import com.example.shopsecure.dto.request.VerifyEmailRequest;
import com.example.shopsecure.dto.response.AuthChallengeResponse;
import com.example.shopsecure.dto.response.AuthResponse;
import com.example.shopsecure.dto.response.MessageResponse;
import com.example.shopsecure.dto.response.VirtualAccountDetailsResponse;
import com.example.shopsecure.entity.OtpPurpose;
import com.example.shopsecure.entity.Role;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.entity.VirtualAccount;
import com.example.shopsecure.entity.Wallet;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.EmailNotVerifiedException;
import com.example.shopsecure.exception.OtpVerificationException;
import com.example.shopsecure.provider.payment.VirtualAccountProvider;
import com.example.shopsecure.provider.payment.VirtualAccountRequest;
import com.example.shopsecure.repository.UserRepository;
import com.example.shopsecure.repository.VirtualAccountRepository;
import com.example.shopsecure.repository.WalletRepository;
import com.example.shopsecure.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private static final String RESET_REQUEST_MESSAGE =
            "If an account exists for that email, a password reset code has been sent.";

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final VirtualAccountProvider virtualAccountProvider;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final OtpService otpService;
    private final AuthNotificationService authNotificationService;
    private final PlatformTransactionManager transactionManager;

    public AuthChallengeResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        PendingEmailCode pending;
        try {
            pending = transaction().execute(status -> {
                User existing = userRepository.findByEmailForUpdate(email).orElse(null);
                if (existing != null) {
                    if (existing.isEnabled()) {
                        throw new BusinessException("Email already registered");
                    }
                    return issueEmailCode(existing, OtpPurpose.EMAIL_VERIFICATION);
                }

                User user = userRepository.save(User.builder()
                        .firstname(request.getFirstname().trim())
                        .lastname(request.getLastname().trim())
                        .email(email)
                        .phoneNumber(request.getPhoneNumber().trim())
                        .password(passwordEncoder.encode(request.getPassword()))
                        .role(Role.USER)
                        .createdAt(LocalDateTime.now())
                        .build());
                log.info("Pending account created: userId={}", user.getId());
                return issueEmailCode(user, OtpPurpose.EMAIL_VERIFICATION);
            });
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException("Email already registered");
        }
        if (pending == null) {
            throw new IllegalStateException("Registration challenge was not created");
        }
        sendEmailCode(pending, OtpPurpose.EMAIL_VERIFICATION);
        return challengeResponse(pending, "Verification code sent");
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public AuthResponse verifyEmail(VerifyEmailRequest request) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmailForUpdate(email)
                .orElseThrow(() -> new OtpVerificationException("Invalid or expired verification code"));
        if (user.isEnabled()) {
            throw new BusinessException("Email is already verified");
        }
        otpService.verify(
                OtpPurpose.EMAIL_VERIFICATION,
                otpSubject(user),
                request.challengeId(),
                request.code()
        );
        user.setEmailVerifiedAt(LocalDateTime.now());
        userRepository.save(user);
        VirtualAccount virtualAccount = provisionAccount(user);
        String token = jwtService.generateToken(user);
        log.info("Account email verified: userId={}", user.getId());
        return response(user, virtualAccount, token, "Email verified successfully");
    }

    public AuthChallengeResponse resendVerification(EmailRequest request) {
        String email = normalizeEmail(request.email());
        PendingEmailCode pending = transaction().execute(status -> {
            User user = userRepository.findByEmailForUpdate(email)
                    .orElseThrow(() -> new BusinessException("Pending account not found"));
            if (user.isEnabled()) {
                throw new BusinessException("Email is already verified");
            }
            return issueEmailCode(user, OtpPurpose.EMAIL_VERIFICATION);
        });
        if (pending == null) {
            throw new IllegalStateException("Verification challenge was not created");
        }
        sendEmailCode(pending, OtpPurpose.EMAIL_VERIFICATION);
        return challengeResponse(pending, "New verification code sent");
    }

    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.getEmail());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("Login failed - invalid credentials");
            throw new BadCredentialsException("Invalid email or password");
        }
        if (!user.isEnabled()) {
            throw new EmailNotVerifiedException();
        }

        VirtualAccount virtualAccount = virtualAccountRepository.findByUser_Id(user.getId()).orElse(null);
        String token = jwtService.generateToken(user);
        log.info("Account logged in successfully: userId={}", user.getId());
        return response(user, virtualAccount, token, "Login Successful");
    }

    public MessageResponse forgotPassword(EmailRequest request) {
        String email = normalizeEmail(request.email());
        PendingEmailCode pending = null;
        try {
            pending = transaction().execute(status -> userRepository.findByEmailForUpdate(email)
                    .map(user -> issueEmailCode(user, OtpPurpose.PASSWORD_RESET))
                    .orElse(null));
        } catch (BusinessException ignored) {
            // Cooldown responses remain indistinguishable from unknown accounts.
        }
        if (pending != null) {
            sendEmailCode(pending, OtpPurpose.PASSWORD_RESET);
        }
        return new MessageResponse(RESET_REQUEST_MESSAGE);
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public MessageResponse resetPassword(ResetPasswordRequest request) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmailForUpdate(email)
                .orElseThrow(() -> new OtpVerificationException("Invalid or expired verification code"));
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new BusinessException("New password must be different from the current password");
        }
        otpService.verifyLatest(OtpPurpose.PASSWORD_RESET, otpSubject(user), request.code());
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setAuthVersion(user.getAuthVersion() + 1);
        userRepository.save(user);
        log.info("Account password reset: userId={}", user.getId());
        return new MessageResponse("Password reset successful. Sign in with your new password.");
    }

    private PendingEmailCode issueEmailCode(User user, OtpPurpose purpose) {
        OtpService.IssuedOtp issued = otpService.issueEmail(purpose, otpSubject(user), user.getEmail());
        return new PendingEmailCode(user.getEmail(), issued);
    }

    private void sendEmailCode(PendingEmailCode pending, OtpPurpose purpose) {
        authNotificationService.sendEmailCode(
                pending.email(),
                pending.issued().code(),
                purpose
        );
    }

    private AuthChallengeResponse challengeResponse(PendingEmailCode pending, String message) {
        return new AuthChallengeResponse(
                pending.issued().challengeId(),
                maskEmail(pending.email()),
                pending.issued().expiresInSeconds(),
                message
        );
    }

    private VirtualAccount provisionAccount(User user) {
        if (walletRepository.findByUser_Id(user.getId()).isEmpty()) {
            Instant now = Instant.now();
            walletRepository.save(Wallet.builder()
                    .user(user)
                    .balance(BigDecimal.ZERO.setScale(2))
                    .currency("NGN")
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }

        VirtualAccount existing = virtualAccountRepository.findByUser_Id(user.getId()).orElse(null);
        if (existing != null) {
            return existing;
        }
        var issuedAccount = virtualAccountProvider.createVirtualAccount(new VirtualAccountRequest(
                user.getFirstname() + " " + user.getLastname(),
                user.getEmail(),
                user.getPhoneNumber(),
                "user-" + user.getId()
        ));
        return virtualAccountRepository.save(VirtualAccount.builder()
                .user(user)
                .accountNumber(issuedAccount.accountNumber())
                .bankName(issuedAccount.bankName())
                .accountName(issuedAccount.accountName())
                .expiresAt(issuedAccount.expiresAt())
                .createdAt(Instant.now())
                .build());
    }

    private AuthResponse response(User user, VirtualAccount virtualAccount, String token, String message) {
        return AuthResponse.builder()
                .token(token)
                .message(message)
                .userId(user.getId())
                .firstname(user.getFirstname())
                .lastname(user.getLastname())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .role(user.getRole())
                .virtualAccount(toResponse(virtualAccount))
                .build();
    }

    private VirtualAccountDetailsResponse toResponse(VirtualAccount account) {
        if (account == null) {
            return null;
        }
        return new VirtualAccountDetailsResponse(
                account.getAccountNumber(),
                account.getBankName(),
                account.getAccountName(),
                account.getExpiresAt()
        );
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private String otpSubject(User user) {
        return "user:" + user.getId();
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String maskEmail(String email) {
        int separator = email.indexOf('@');
        String local = email.substring(0, separator);
        String visible = local.substring(0, 1);
        return visible + "***" + email.substring(separator);
    }

    private record PendingEmailCode(String email, OtpService.IssuedOtp issued) {
    }
}
