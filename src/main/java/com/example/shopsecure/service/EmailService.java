package com.example.shopsecure.service;

import com.example.shopsecure.exception.BusinessException;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import org.springframework.beans.factory.annotation.Value;
import com.resend.services.emails.model.CreateEmailOptions;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    @Value("${resend.api.key}")
    private String apiKey;

    public void sendEmail(String to, String subject, String htmlContent) {
        Resend resend = new Resend(apiKey);

        CreateEmailOptions request = CreateEmailOptions.builder()
                .from("ShopSecure <onboarding@resend.dev>")
                .to(to)
                .subject(subject)
                .html(htmlContent)
                .build();

        try {
            resend.emails().send(request);
        } catch (ResendException e) {
            throw new BusinessException("Failed to send email: " + e.getMessage()); // ← changed
        }
    }
}