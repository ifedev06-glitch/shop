package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.*;
import com.example.shopsecure.dto.response.AuthChallengeResponse;
import com.example.shopsecure.dto.response.AuthResponse;
import com.example.shopsecure.dto.response.MessageResponse;
import com.example.shopsecure.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthChallengeResponse> register(
            @Valid @RequestBody RegisterRequest request) {
        log.info("Account registration attempt");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(authService.register(request));
    }

    @PostMapping("/verify-email")
    public AuthResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return authService.verifyEmail(request);
    }

    @PostMapping("/resend-verification")
    public AuthChallengeResponse resendVerification(@Valid @RequestBody EmailRequest request) {
        return authService.resendVerification(request);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request) {
        log.info("Account login attempt");
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse forgotPassword(@Valid @RequestBody EmailRequest request) {
        return authService.forgotPassword(request);
    }

    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return authService.resetPassword(request);
    }
}
