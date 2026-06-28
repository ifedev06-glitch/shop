package com.example.shopsecure.repository;

import com.example.shopsecure.entity.ShippingRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShippingRateRepository extends JpaRepository<ShippingRate, Long> {

    List<ShippingRate> findByActiveTrue();

    Optional<ShippingRate> findByAreaAndActiveTrue(String area);

}
