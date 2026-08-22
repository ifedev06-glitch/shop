package com.example.shopsecure.controller;

import com.example.shopsecure.dto.response.ShippingCityResponse;
import com.example.shopsecure.dto.response.ShippingStateResponse;
import com.example.shopsecure.service.ShippingLocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/locations")
@RequiredArgsConstructor
public class ShippingLocationController {

    private static final CacheControl PUBLIC_CACHE = CacheControl
            .maxAge(Duration.ofHours(1))
            .cachePublic();

    private final ShippingLocationService locationService;

    @GetMapping("/states")
    public ResponseEntity<List<ShippingStateResponse>> getStates() {
        return ResponseEntity.ok()
                .cacheControl(PUBLIC_CACHE)
                .body(locationService.getStates());
    }

    @GetMapping("/states/{stateCode}/cities")
    public ResponseEntity<List<ShippingCityResponse>> getCities(
            @PathVariable String stateCode
    ) {
        return ResponseEntity.ok()
                .cacheControl(PUBLIC_CACHE)
                .body(locationService.getCities(stateCode));
    }
}
