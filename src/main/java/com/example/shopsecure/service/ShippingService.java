package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.CreateShippingRateRequest;
import com.example.shopsecure.dto.request.UpdateShippingFeeRequest;
import com.example.shopsecure.dto.response.ShippingAreaResponse;
import com.example.shopsecure.entity.ShippingRate;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.ShippingRateRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ShippingService {

    private final ShippingRateRepository shippingRateRepository;

    public List<ShippingAreaResponse> getAvailableAreas() {

        return shippingRateRepository.findByActiveTrue()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    public BigDecimal getShippingFee(String area) {

        ShippingRate shippingRate = shippingRateRepository
                .findByAreaAndActiveTrue(area)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Shipping rate not found for area: " + area
                        ));

        return shippingRate.getFee();
    }

    @Transactional
    public ShippingAreaResponse createShippingRate(
            CreateShippingRateRequest request) {

        ShippingRate shippingRate = ShippingRate.builder()
                .area(request.getArea().toUpperCase())
                .fee(request.getFee())
                .active(true)
                .build();

        shippingRateRepository.save(shippingRate);
        log.info("Shipping rate created for area: {}", request.getArea().toUpperCase());

        return mapToResponse(shippingRate);
    }

    @Transactional
    public List<ShippingAreaResponse> createShippingRates(
            List<CreateShippingRateRequest> requests) {

        List<ShippingRate> rates = requests.stream()
                .map(r -> ShippingRate.builder()
                        .area(r.getArea().toUpperCase())
                        .fee(r.getFee())
                        .active(true)
                        .build())
                .toList();

        shippingRateRepository.saveAll(rates);

        return rates.stream()
                .map(this::mapToResponse)
                .toList();
    }


    @Transactional
    public ShippingAreaResponse updateShippingFee(
            Long id,
            UpdateShippingFeeRequest request) {

        ShippingRate shippingRate = shippingRateRepository
                .findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Shipping rate not found"));

        shippingRate.setFee(request.getFee());

        shippingRateRepository.save(shippingRate);

        return mapToResponse(shippingRate);
    }

    public ShippingRate getShippingRate(String area) {
        log.info("Shipping fee calculated for area: {}", area);

        return shippingRateRepository
                .findByAreaAndActiveTrue(area.toUpperCase())
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Shipping rate not found for area: " + area
                        ));
    }


    private ShippingAreaResponse mapToResponse(ShippingRate shippingRate) {

        return ShippingAreaResponse.builder()
                .id(shippingRate.getId())
                .area(shippingRate.getArea())
                .fee(shippingRate.getFee())
                .build();
    }
}
