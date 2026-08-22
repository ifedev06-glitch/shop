package com.example.shopsecure.integration;

import com.example.shopsecure.ShopsecureApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {ShopsecureApplication.class, TestConfig.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityRegressionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unknownLegacyPublicEndpointIsNotExposed() throws Exception {
        mockMvc.perform(get("/api/public/legacy-login/2348012345678"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    @Test
    void legacyPublicOrderStatusEndpointIsNotPublic() throws Exception {
        mockMvc.perform(put("/api/checkout/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    @Test
    void shippingLocationCatalogueIsPublicAndCacheable() throws Exception {
        mockMvc.perform(get("/api/locations/states"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=3600, public"))
                .andExpect(jsonPath("$[0].code").isNotEmpty());

        mockMvc.perform(get("/api/locations/states/LA/cities"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=3600, public"))
                .andExpect(jsonPath("$[0].stateCode").value("LA"));
    }

    @Test
    void orderTrackingCapabilityIsReadOnlyAndNeverCacheable() throws Exception {
        mockMvc.perform(get("/orders/tracking/not-a-valid-token"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow, noarchive"));

        mockMvc.perform(post("/orders/tracking/not-a-valid-token"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://shopsecure.app", "https://www.shopsecure.app"})
    void productionFrontendOriginsPassCorsPreflight(String origin) throws Exception {
        mockMvc.perform(options("/api/auth/register")
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin));
    }

}
