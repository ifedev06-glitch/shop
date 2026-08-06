package com.example.shopsecure.service;

import com.example.shopsecure.event.VendorInfoCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderNotificationService {

    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendVendorInfoCompletedEmail(VendorInfoCompletedEvent event) {
        try {
            emailService.sendEmail(
                    event.customerEmail(),
                    "Vendor accepted - order ready for payment",
                    emailTemplateService.vendorInfoCompletedBody(
                            event.customerFirstName(),
                            event.itemDescription()
                    )
            );
        } catch (RuntimeException exception) {
            log.error("Unable to send vendor information completion email", exception);
        }
    }
}
