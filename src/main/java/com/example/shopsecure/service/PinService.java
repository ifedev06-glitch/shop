package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.ChangePinRequest;
import com.example.shopsecure.dto.request.CreatePinRequest;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PinService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public void createPin(CreatePinRequest request) {
        if (!request.getPin().equals(request.getConfirmPin())) {
            throw new RuntimeException("PINs do not match");
        }

        User user = getCurrentUser();

        if (user.getTransactionPin() != null) {
            throw new RuntimeException("PIN already exists. Use change PIN instead.");
        }

        user.setTransactionPin(passwordEncoder.encode(request.getPin()));
        userRepository.save(user);
    }

    public void changePin(@Valid ChangePinRequest request){
        User user = getCurrentUser();

        if (!passwordEncoder.matches(request.getOldPin(), user.getTransactionPin())) {
            throw new RuntimeException("Incorrect current PIN");
        }

        if (!request.getNewPin().equals(request.getConfirmNewPin())) {
            throw new RuntimeException("New PINs do not match");
        }

        user.setTransactionPin(passwordEncoder.encode(request.getNewPin()));
        userRepository.save(user);
    }

    public void verifyPin(String rawPin) {
        User user = getCurrentUser();

        if (user.getTransactionPin() == null) {
            throw new RuntimeException("No transaction PIN set. Please create one first.");
        }

        if (!passwordEncoder.matches(rawPin, user.getTransactionPin())) {
            throw new RuntimeException("Incorrect PIN");
        }
    }

    public boolean hasPin() {
        User user = getCurrentUser();
        return user.getTransactionPin() != null;
    }

}