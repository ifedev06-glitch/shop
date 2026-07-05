package com.example.shopsecure.controller;

import com.example.shopsecure.dto.request.LinkWhatsAppRequest;
import com.example.shopsecure.dto.response.WhatsAppLinkResponse;
import com.example.shopsecure.service.WhatsAppLinkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class WhatsAppLinkController {

    private final WhatsAppLinkService whatsAppLinkService;

    @PostMapping("/api/vendor/whatsapp-link")
    @ResponseStatus(HttpStatus.CREATED)
    public WhatsAppLinkResponse linkWhatsApp(
            @Valid @RequestBody LinkWhatsAppRequest request
    ) {
        log.info("Linking WhatsApp for vendor");
        return whatsAppLinkService.linkWhatsApp(request.getPhoneNumber());
    }

    @GetMapping("/api/vendor/whatsapp-link")
    public WhatsAppLinkResponse getLinkedWhatsApp() {
        log.info("Fetching linked WhatsApp for vendor");
        return whatsAppLinkService.getLinkedWhatsApp();
    }

    @DeleteMapping("/api/vendor/whatsapp-link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkWhatsApp() {
        log.info("Unlinking WhatsApp for vendor");
        whatsAppLinkService.unlinkWhatsApp();
    }

    @GetMapping("/api/public/whatsapp-link/{phone}")
    public Map<String, String> getTokenByPhone(
            @PathVariable String phone
    ) {
        log.info("Token request for WhatsApp phone={}", phone);
        return whatsAppLinkService.getTokenByPhone(phone);
    }
}
