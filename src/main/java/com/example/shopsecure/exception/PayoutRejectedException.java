package com.example.shopsecure.exception;

public class PayoutRejectedException extends PaymentException {
    public PayoutRejectedException() {
        super("Payment provider rejected payout request");
    }
}
