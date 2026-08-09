package com.example.shopsecure.integration;

import com.example.shopsecure.ShopsecureApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

}
