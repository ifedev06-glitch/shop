package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.ChangePinRequest;
import com.example.shopsecure.dto.request.CreatePinRequest;
import com.example.shopsecure.service.PinService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/vendor/pin")
@RequiredArgsConstructor
public class PinController {

    private final PinService pinService;

    @PostMapping("/create")
    public ResponseEntity<String> createPin(@Valid @RequestBody CreatePinRequest request) {
        pinService.createPin(request);
        return ResponseEntity.ok("Transaction PIN created successfully.");
    }

    @PutMapping("/change")
    public ResponseEntity<String> changePin(@Valid @RequestBody ChangePinRequest request) {
        pinService.changePin(request);
        return ResponseEntity.ok("Transaction PIN changed successfully.");
    }

    @GetMapping("/status")
    public ResponseEntity<?> hasPin() {
        boolean hasPin = pinService.hasPin();
        return ResponseEntity.ok(Map.of("hasPin", hasPin));
    }

}
