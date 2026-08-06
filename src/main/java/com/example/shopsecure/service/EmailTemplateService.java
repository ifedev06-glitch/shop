package com.example.shopsecure.service;

import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

@Service
public class EmailTemplateService {

    public String vendorInfoCompletedBody(String customerFirstName, String itemDescription) {
        String safeName = HtmlUtils.htmlEscape(
                customerFirstName == null || customerFirstName.isBlank() ? "there" : customerFirstName
        );
        String safeDescription = HtmlUtils.htmlEscape(itemDescription);
        return """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #16a34a;">Vendor accepted your order</h2>
                    <p>Hi <strong>%s</strong>,</p>
                    <p>The vendor has verified their phone number and accepted your order.</p>
                    <div style="background: #f8fafc; border-radius: 8px; padding: 16px; margin: 16px 0;">
                        <p><strong>Item:</strong> %s</p>
                        <p><strong>Status:</strong> Ready for wallet payment</p>
                    </div>
                    <p>You can now pay from your ShopSecure wallet to move the order amount into escrow.</p>
                </div>
                """.formatted(safeName, safeDescription);
    }
}
