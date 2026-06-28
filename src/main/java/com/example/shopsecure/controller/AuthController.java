package com.example.shopsecure.controller;

import com.example.shopsecure.dto.response.AuthResponse;
import com.example.shopsecure.dto.request.LoginRequest;
import com.example.shopsecure.dto.request.RegisterRequest;
import com.example.shopsecure.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request) {
        log.info("Merchant registration attempt: email={}", request.getEmail());
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request){
        log.info("Merchant login attempt: email={}", request.getEmail());
        return ResponseEntity.ok(authService.login(request));
    }
}
