package com.example.shopsecure.controller;

import com.example.shopsecure.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Slf4j
public class EmailTestController {

    private final EmailService emailService;


    @PostMapping("/email")
    public String testEmail() {

        String recipient = "ifeoladip@gmail.com";
        log.info("Sending test email to={}", recipient);

        emailService.sendEmail(
                recipient,
                "ShopSecure SMTP Test",
                "This is a test email from your Spring Boot app. If you see this, SMTP is working 🎉"
        );

        log.info("Test email sent successfully to={}", recipient);
        return "Email sent to " + recipient;
    }
}