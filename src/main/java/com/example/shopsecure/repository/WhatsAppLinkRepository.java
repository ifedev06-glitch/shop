package com.example.shopsecure.repository;

import com.example.shopsecure.entity.WhatsAppLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WhatsAppLinkRepository
        extends JpaRepository<WhatsAppLink, Long> {

    Optional<WhatsAppLink> findByBusiness_Id(Long businessId);

    Optional<WhatsAppLink> findByPhoneNumber(String phoneNumber);
}
