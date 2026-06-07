package com.example.shopsecure.service;

import org.springframework.stereotype.Service;

@Service
public class EmailTemplateService {

    public String customerOrderConfirmationBody(String customerName, String orderNumber, String totalAmount) {
        return """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #16a34a;">Order Confirmed!</h2>
                    <p>Hi <strong>%s</strong>,</p>
                    <p>Your order has been successfully placed.</p>
                    <div style="background: #f8fafc; border-radius: 8px; padding: 16px; margin: 16px 0;">
                        <p><strong>Order Number:</strong> %s</p>
                        <p><strong>Total Amount:</strong> %s</p>
                    </div>
                    <p>We will notify you once your order is processed.</p>
                    <p>Thank you for shopping with <strong>ShopSecure</strong>!</p>
                </div>
                """.formatted(customerName, orderNumber, totalAmount);
    }

    public String sellerNewOrderBody(String customerName, String orderNumber, String totalAmount) {
        return """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #16a34a;">New Order Received!</h2>
                    <p>You have received a new order on <strong>ShopSecure</strong>.</p>
                    <div style="background: #f8fafc; border-radius: 8px; padding: 16px; margin: 16px 0;">
                        <p><strong>Order Number:</strong> %s</p>
                        <p><strong>Customer:</strong> %s</p>
                        <p><strong>Total:</strong> %s</p>
                    </div>
                    <p>Please log in to your dashboard to process the order.</p>
                </div>
                """.formatted(orderNumber, customerName, totalAmount);
    }

    public String orderShippedBody(String customerName, String orderNumber, String phoneNumber) {
        return """
            <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                <h2 style="color: #16a34a;">Your Order is On Its Way!</h2>
                <p>Hi <strong>%s</strong>,</p>
                <p>Great news! Your order has been picked up and is now on its way to you.</p>
                <div style="background: #f8fafc; border-radius: 8px; padding: 16px; margin: 16px 0;">
                    <p><strong>Order Number:</strong> %s</p>
                    <p><strong>Status:</strong> Out for Delivery</p>
                </div>
                <p>Our delivery team will call you on <strong>%s</strong> when they are close.</p>
                <p>Please ensure someone is available to receive the order.</p>
                <p>Thank you for shopping with <strong>ShopSecure</strong>!</p>
            </div>
            """.formatted(customerName, orderNumber, phoneNumber);
    }

    public String orderDeliveredBody(String customerName, String orderNumber, String totalAmount) {
        return """
            <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                <h2 style="color: #16a34a;">Order Delivered!</h2>
                <p>Hi <strong>%s</strong>,</p>
                <p>Your order has been successfully delivered. We hope you love your purchase!</p>
                <div style="background: #f8fafc; border-radius: 8px; padding: 16px; margin: 16px 0;">
                    <p><strong>Order Number:</strong> %s</p>
                    <p><strong>Total Paid:</strong> %s</p>
                    <p><strong>Status:</strong> Delivered</p>
                </div>
                <p>If you have any issues with your order, please contact us at <a href="mailto:support@shopsecure.com">support@shopsecure.com</a></p>
                <p>Thank you for shopping with <strong>ShopSecure</strong>!</p>
            </div>
            """.formatted(customerName, orderNumber, totalAmount);
    }

}
