package com.example.shopsecure.controller;

import com.example.shopsecure.config.SecurityConfig;
import com.example.shopsecure.dto.response.ShippingCityResponse;
import com.example.shopsecure.dto.response.ShippingStateResponse;
import com.example.shopsecure.exception.ServiceUnavailableException;
import com.example.shopsecure.security.JwtService;
import com.example.shopsecure.service.ShippingLocationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ShippingLocationController.class,
        properties = "rate-limit.enabled=false"
)
@Import(SecurityConfig.class)
class ShippingLocationControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShippingLocationService locationService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void anonymousUsersCanReadCacheableStatesAndCities() throws Exception {
        when(locationService.getStates()).thenReturn(List.of(
                new ShippingStateResponse("LA", "Lagos")
        ));
        when(locationService.getCities("LA")).thenReturn(List.of(
                new ShippingCityResponse("Ikeja", "LA")
        ));

        mockMvc.perform(get("/api/locations/states"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=3600, public"))
                .andExpect(jsonPath("$[0].code").value("LA"))
                .andExpect(jsonPath("$[0].name").value("Lagos"));
        mockMvc.perform(get("/api/locations/states/LA/cities"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=3600, public"))
                .andExpect(jsonPath("$[0].name").value("Ikeja"))
                .andExpect(jsonPath("$[0].stateCode").value("LA"));

        verify(locationService).getStates();
        verify(locationService).getCities("LA");
    }

    @Test
    void locationMutationsRemainProtected() throws Exception {
        int responseStatus = mockMvc.perform(post("/api/locations/states"))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(responseStatus).isIn(401, 403);
    }

    @Test
    void providerOutageReturnsServiceUnavailable() throws Exception {
        when(locationService.getStates()).thenThrow(
                new ServiceUnavailableException("Shipping locations are currently unavailable")
        );

        mockMvc.perform(get("/api/locations/states"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Shipping locations are currently unavailable"));
    }
}
