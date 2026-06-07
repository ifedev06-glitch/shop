package com.example.shopsecure.controller;

import com.example.shopsecure.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class EmailTestController {

    private final EmailService emailService;


    @PostMapping("/email")
    public String testEmail() {

        String recipient = "ifeoladip@gmail.com";

        emailService.sendEmail(
                recipient,
                "ShopSecure SMTP Test",
                "This is a test email from your Spring Boot app. If you see this, SMTP is working 🎉"
        );

        return "Email sent to " + recipient;
    }
}