package com.example.shopsecure.repository;

import com.example.shopsecure.entity.CheckoutLinkItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CheckoutLinkItemRepository
        extends JpaRepository<CheckoutLinkItem, Long> {

    List<CheckoutLinkItem> findByCheckoutLink_Id(Long checkoutLinkId);
}