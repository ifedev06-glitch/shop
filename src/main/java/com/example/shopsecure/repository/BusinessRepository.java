package com.example.shopsecure.repository;

import com.example.shopsecure.entity.Business;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BusinessRepository extends JpaRepository<Business, Long> {

    boolean existsByStorefrontSlug(String storefrontSlug);

    Optional<Business> findByOwner_Id(Long ownerId);

    Optional<Business> findByStorefrontSlug(String storefrontSlug);
}