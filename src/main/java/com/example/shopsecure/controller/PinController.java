package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.ChangePinRequest;
import com.example.shopsecure.dto.request.CreatePinRequest;
import com.example.shopsecure.service.PinService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/vendor/pin")
@RequiredArgsConstructor
@Slf4j
public class PinController {

    private final PinService pinService;

    @PostMapping("/create")
    public ResponseEntity<String> createPin(@Valid @RequestBody CreatePinRequest request) {
        log.info("Transaction PIN creation request");
        pinService.createPin(request);
        log.info("Transaction PIN created successfully");
        return ResponseEntity.ok("Transaction PIN created successfully.");
    }

    @PutMapping("/change")
    public ResponseEntity<String> changePin(@Valid @RequestBody ChangePinRequest request) {
        log.info("Transaction PIN change request");
        pinService.changePin(request);
        log.info("Transaction PIN changed successfully");
        return ResponseEntity.ok("Transaction PIN changed successfully.");
    }

    @GetMapping("/status")
    public ResponseEntity<?> hasPin() {
        log.info("Checking transaction PIN status");
        boolean hasPin = pinService.hasPin();
        return ResponseEntity.ok(Map.of("hasPin", hasPin));
    }

}
