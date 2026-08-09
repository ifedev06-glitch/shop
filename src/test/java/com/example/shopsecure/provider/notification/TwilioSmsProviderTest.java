package com.example.shopsecure.provider.notification;

import com.example.shopsecure.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TwilioSmsProviderTest {

    private static final String ACCOUNT_SID = "AC123456789";
    private static final String AUTH_TOKEN = "test-auth-token";
    private static final String FROM_NUMBER = "+15005550006";
    private static final String BASE_URL = "https://api.twilio.test";
    private static final String MESSAGES_URL = BASE_URL
            + "/2010-04-01/Accounts/"
            + ACCOUNT_SID
            + "/Messages.json";

    private MockRestServiceServer server;
    private TwilioSmsProvider provider;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        provider = new TwilioSmsProvider(
                restTemplate,
                ACCOUNT_SID,
                AUTH_TOKEN,
                FROM_NUMBER,
                BASE_URL + "/"
        );
    }

    @Test
    void sendsExactFormEncodedRequestWithBasicAuth() {
        String expectedCredentials = Base64.getEncoder().encodeToString(
                (ACCOUNT_SID + ":" + AUTH_TOKEN).getBytes(StandardCharsets.UTF_8)
        );
        server.expect(once(), requestTo(MESSAGES_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic " + expectedCredentials))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(
                        "To=%2B2348012345678&From=%2B15005550006&Body=Your+code+is+123456"
                ))
                .andRespond(withSuccess("{\"sid\":\"SM123456789\"}", MediaType.APPLICATION_JSON));

        provider.sendMessage("+2348012345678", "Your code is 123456");

        server.verify();
    }

    @Test
    void prefixesDigitsOnlyPhoneNumber() {
        server.expect(once(), requestTo(MESSAGES_URL))
                .andExpect(content().string(
                        "To=%2B2348012345678&From=%2B15005550006&Body=Verification+code"
                ))
                .andRespond(withSuccess("{\"sid\":\"SM123456789\"}", MediaType.APPLICATION_JSON));

        provider.sendMessage("2348012345678", "Verification code");

        server.verify();
    }

    @Test
    void rejectsMissingPhoneNumberAndMessageWithoutCallingProvider() {
        assertThatThrownBy(() -> provider.sendMessage(null, "Verification code"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Phone number is required");
        assertThatThrownBy(() -> provider.sendMessage("   ", "Verification code"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Phone number is required");
        assertThatThrownBy(() -> provider.sendMessage("+2348012345678", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Message is required");
        assertThatThrownBy(() -> provider.sendMessage("+2348012345678", "   "))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Message is required");
        server.verify();
    }

    @Test
    void rejectsSuccessfulResponseWithoutNonblankSid() {
        server.expect(once(), requestTo(MESSAGES_URL))
                .andRespond(withSuccess("{\"sid\":\"   \"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.sendMessage("+2348012345678", "Verification code"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("SMS provider returned an invalid response");
        server.verify();
    }

    @Test
    void translatesHttpProviderFailuresToSanitizedBusinessExceptionWithoutRetrying() {
        server.expect(once(), requestTo(MESSAGES_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Rejected token and message for +2348012345678"));
        server.expect(once(), requestTo(MESSAGES_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Failed with provider credentials and request body"));

        assertThatThrownBy(() -> provider.sendMessage("+2348012345678", "Secret verification code"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("SMS provider request failed")
                .hasMessageNotContaining(AUTH_TOKEN)
                .hasMessageNotContaining("Secret verification code")
                .hasMessageNotContaining("+2348012345678");
        assertThatThrownBy(() -> provider.sendMessage("+2348012345678", "Secret verification code"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("SMS provider request failed")
                .hasMessageNotContaining(AUTH_TOKEN)
                .hasMessageNotContaining("Secret verification code")
                .hasMessageNotContaining("+2348012345678");
        server.verify();
    }

    @Test
    void translatesTimeoutToSanitizedBusinessExceptionWithoutRetrying() {
        server.expect(once(), requestTo(MESSAGES_URL))
                .andRespond(request -> {
                    throw new ResourceAccessException(
                            "Timed out sending Secret verification code to +2348012345678"
                    );
                });

        assertThatThrownBy(() -> provider.sendMessage("+2348012345678", "Secret verification code"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("SMS provider request failed")
                .hasMessageNotContaining("Secret verification code")
                .hasMessageNotContaining("+2348012345678");
        server.verify();
    }
}
