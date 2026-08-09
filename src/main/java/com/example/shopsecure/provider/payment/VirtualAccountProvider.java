package com.example.shopsecure.provider.payment;

public interface VirtualAccountProvider {
    VirtualAccountResponse createVirtualAccount(VirtualAccountRequest request);
}
