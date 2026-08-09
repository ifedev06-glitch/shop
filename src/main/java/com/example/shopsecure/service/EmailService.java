package com.example.shopsecure.service;

import com.example.shopsecure.exception.BusinessException;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import org.springframework.beans.factory.annotation.Value;
import com.resend.services.emails.model.Attachment;
import com.resend.services.emails.model.CreateEmailOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;

@Slf4j
@Service
public class EmailService {

    @Value("${resend.api.key}")
    private String apiKey;

    public void sendEmail(String to, String subject, String htmlContent) {
        Resend resend = new Resend(apiKey);

        CreateEmailOptions request = CreateEmailOptions.builder()
                .from("ShopSecure <support@shopsecure.app>")
                .to(to)
                .subject(subject)
                .html(htmlContent)
                .build();

        try {
            resend.emails().send(request);
            log.info("Email sent: subject={}", subject);
        } catch (ResendException e) {
            log.error("Failed to send email: subject={}", subject, e);
            throw new BusinessException("Failed to send email: " + e.getMessage());
        }
    }

    public void sendEmailWithAttachment(String to, String subject, String htmlContent, byte[] pdfAttachment, String filename) {
        Resend resend = new Resend(apiKey);

        Attachment attachment = Attachment.builder()
                .fileName(filename)
                .content(Base64.getEncoder().encodeToString(pdfAttachment))
                .build();

        CreateEmailOptions request = CreateEmailOptions.builder()
                .from("ShopSecure <support@shopsecure.app>")
                .to(to)
                .subject(subject)
                .html(htmlContent)
                .attachments(List.of(attachment))
                .build();

        try {
            resend.emails().send(request);
            log.info("Email sent with attachment: subject={}", subject);
        } catch (ResendException e) {
            log.error("Failed to send email with attachment: subject={}", subject, e);
            throw new BusinessException("Failed to send email: " + e.getMessage());
        }
    }
}
