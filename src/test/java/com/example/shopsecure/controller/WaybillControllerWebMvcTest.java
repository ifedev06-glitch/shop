package com.example.shopsecure.controller;

import com.example.shopsecure.config.SecurityConfig;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.security.JwtService;
import com.example.shopsecure.service.WaybillAccessService;
import com.example.shopsecure.service.WaybillCancellationService;
import com.example.shopsecure.service.WaybillCheckoutService;
import com.example.shopsecure.service.WaybillService;
import com.example.shopsecure.service.WaybillShippingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {WaybillController.class, PublicWaybillController.class},
        properties = "rate-limit.enabled=false"
)
@Import(SecurityConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class WaybillControllerWebMvcTest {

    private static final String PUBLIC_ID = "waybill-public-id";
    private static final String INVITATION_TOKEN = "invitation-token-secret";
    private static final String ACCESS_TOKEN = "waybill-access-token";
    private static final String IDEMPOTENCY_KEY = "idempotency-key";
    private static final String CREATE_REQUEST = """
            {
              "creatorRole": "SENDER",
              "payerRole": "SENDER",
              "description": "A parcel",
              "declaredValue": 2500.00,
              "creatorAddress": {
                "line1": "1 Marina Road",
                "city": "Lagos",
                "state": "Lagos",
                "country": "NG",
                "postalCode": "100001"
              },
              "parcel": {
                "weightKg": 1.500,
                "lengthCm": 20.00,
                "widthCm": 15.00,
                "heightCm": 10.00
              },
              "oppositeParty": {
                "firstName": "Recipient",
                "lastName": "Person",
                "email": "recipient@example.com",
                "phoneNumber": "+2348098765432",
                "address": {
                  "line1": "2 Broad Street",
                  "city": "Abuja",
                  "state": "FCT",
                  "country": "NG",
                  "postalCode": "900001"
                }
              }
            }
            """;
    private static final String VERIFY_REQUEST = """
            {"challengeId":"challenge-id","code":"123456"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WaybillService waybillService;
    @MockitoBean
    private WaybillShippingService shippingService;
    @MockitoBean
    private WaybillCheckoutService checkoutService;
    @MockitoBean
    private WaybillCancellationService cancellationService;
    @MockitoBean
    private WaybillAccessService accessService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void publicInvitationEndpointsAllowAnonymousAccessAndDisableCaching() throws Exception {
        expectPublic(get(invitationPath()), 200);
        expectPublic(post(invitationPath("/payment-access-otp")), 202);
        expectPublic(json(post(invitationPath("/verify-otp")), VERIFY_REQUEST), 200);
        expectPublic(
                json(post(invitationPath("/resend-otp")), "{\"challengeId\":\"challenge-id\"}"),
                200
        );
        expectPublic(post(invitationPath("/access-recovery")), 202);
        expectPublic(json(post(invitationPath("/access-recovery/verify")), VERIFY_REQUEST), 200);
        expectPublic(
                get(invitationPath("/status")).header("X-Waybill-Access-Token", ACCESS_TOKEN),
                200
        );
        expectPublic(
                post(invitationPath("/payments/checkout"))
                        .header("X-Waybill-Access-Token", ACCESS_TOKEN)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY),
                200
        );

        verify(accessService).getInvitation(INVITATION_TOKEN);
        verify(accessService).requestPaymentAccess(INVITATION_TOKEN);
        verify(accessService).verify(INVITATION_TOKEN, "challenge-id", "123456");
        verify(accessService).resendOtp(INVITATION_TOKEN, "challenge-id");
        verify(accessService).requestAccessRecovery(INVITATION_TOKEN);
        verify(accessService).verifyAccessRecovery(INVITATION_TOKEN, "challenge-id", "123456");
        verify(accessService).getAccessStatus(INVITATION_TOKEN, ACCESS_TOKEN);
        verify(checkoutService).initializeInvited(
                INVITATION_TOKEN,
                ACCESS_TOKEN,
                IDEMPOTENCY_KEY
        );
    }

    @Test
    void everyCreatorRouteDelegatesForAnAuthenticatedUser() throws Exception {
        mockMvc.perform(json(post("/waybills")
                        .with(user("creator"))
                        .header("Idempotency-Key", "create-key"), CREATE_REQUEST))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/waybills")
                        .with(user("creator"))
                        .param("page", "3")
                        .param("size", "7"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/waybills/{publicId}", PUBLIC_ID).with(user("creator")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/waybills/{publicId}/history", PUBLIC_ID).with(user("creator")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/waybills/{publicId}/invitations/reissue", PUBLIC_ID)
                        .with(user("creator")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/waybills/{publicId}/shipping/quotes", PUBLIC_ID)
                        .with(user("creator")))
                .andExpect(status().isOk());
        mockMvc.perform(json(put("/waybills/{publicId}/shipping/selection", PUBLIC_ID)
                        .with(user("creator")), "{\"quoteId\":42}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/waybills/{publicId}/payments/checkout", PUBLIC_ID)
                        .with(user("creator"))
                        .header("Idempotency-Key", "checkout-key"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/waybills/{publicId}/shipping/tracking", PUBLIC_ID)
                        .with(user("creator")))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/waybills/{publicId}/cancel", PUBLIC_ID)
                        .with(user("creator"))
                        .header("Idempotency-Key", "cancel-key"), "{\"reason\":\"Changed plans\"}"))
                .andExpect(status().isOk());

        verify(waybillService).create(any(), eq("create-key"));
        verify(waybillService).list(3, 7);
        verify(waybillService).get(PUBLIC_ID);
        verify(waybillService).history(PUBLIC_ID);
        verify(waybillService).reissueInvitation(PUBLIC_ID);
        verify(shippingService).createQuotes(PUBLIC_ID);
        verify(shippingService).selectQuote(PUBLIC_ID, 42L);
        verify(checkoutService).initializeCreator(PUBLIC_ID, "checkout-key");
        verify(shippingService).refreshTracking(PUBLIC_ID);
        verify(cancellationService).request(PUBLIC_ID, "cancel-key", "Changed plans");
    }

    @Test
    void creatorRoutesRequireAuthenticationIncludingCreatorInvitationManagement() throws Exception {
        assertAuthenticationRequired(get("/waybills"));
        assertAuthenticationRequired(get("/waybills/{publicId}", PUBLIC_ID));
        assertAuthenticationRequired(post("/waybills/{publicId}/invitations/reissue", PUBLIC_ID));

        verifyNoInteractions(waybillService, shippingService, checkoutService, cancellationService);
    }

    @Test
    void ownershipDecisionIsDelegatedToTheService() throws Exception {
        when(waybillService.get(PUBLIC_ID))
                .thenThrow(new AccessDeniedException("Waybill does not belong to current user"));

        mockMvc.perform(get("/waybills/{publicId}", PUBLIC_ID).with(user("creator")))
                .andExpect(status().isForbidden());

        verify(waybillService).get(PUBLIC_ID);
    }

    @Test
    void idempotencyAndAccessHeadersAreRequired() throws Exception {
        mockMvc.perform(json(post("/waybills").with(user("creator")), CREATE_REQUEST))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/waybills/{publicId}/payments/checkout", PUBLIC_ID)
                        .with(user("creator")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/waybills/{publicId}/cancel", PUBLIC_ID)
                        .with(user("creator")), "{}"))
                .andExpect(status().isBadRequest());
        expectPublic(get(invitationPath("/status")), 400);
        expectPublic(
                post(invitationPath("/payments/checkout"))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY),
                400
        );
        expectPublic(
                post(invitationPath("/payments/checkout"))
                        .header("X-Waybill-Access-Token", ACCESS_TOKEN),
                400
        );

        verify(waybillService, never()).create(any(), any());
        verify(checkoutService, never()).initializeCreator(any(), any());
        verify(cancellationService, never()).request(any(), any(), any());
        verify(accessService, never()).getAccessStatus(any(), any());
        verify(checkoutService, never()).initializeInvited(any(), any(), any());
    }

    @Test
    void requestDtosAreValidatedBeforeServiceDelegation() throws Exception {
        mockMvc.perform(json(put("/waybills/{publicId}/shipping/selection", PUBLIC_ID)
                        .with(user("creator")), "{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/waybills/{publicId}/cancel", PUBLIC_ID)
                        .with(user("creator"))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY),
                "{\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
        expectPublic(json(post(invitationPath("/resend-otp")), "{}"), 400);
        expectPublic(
                json(
                        post(invitationPath("/verify-otp")),
                        "{\"challengeId\":\"challenge-id\",\"code\":\"12\"}"
                ),
                400
        );
        verifyNoInteractions(shippingService, cancellationService, accessService);
    }

    @Test
    void noStoreHeadersAreScopedToPublicInvitationPaths() throws Exception {
        mockMvc.perform(get("/waybills").with(user("creator")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Referrer-Policy"));

        expectSensitive(json(post("/waybills")
                .with(user("creator"))
                .header("Idempotency-Key", "create-key"), CREATE_REQUEST), 201);
        expectSensitive(post("/waybills/{publicId}/invitations/reissue", PUBLIC_ID)
                .with(user("creator")), 200);
        expectSensitive(post("/waybills/{publicId}/payments/checkout", PUBLIC_ID)
                .with(user("creator"))
                .header("Idempotency-Key", "checkout-key"), 200);
    }

    @Test
    void invitationTokenIsRedactedFromRequestAndExceptionLogs(CapturedOutput output) throws Exception {
        when(accessService.getInvitation(INVITATION_TOKEN))
                .thenThrow(new BusinessException("Invitation rejected for test"));

        expectPublic(get(invitationPath()), 400);

        assertThat(output)
                .contains("Business rule violation: path=GET /waybills/invitations/[redacted]")
                .contains("Request completed with client error: method=GET, "
                        + "path=/waybills/invitations/[redacted]")
                .doesNotContain(INVITATION_TOKEN);
    }

    private void expectPublic(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        expectPublic(mockMvc.perform(request), expectedStatus);
    }

    private void expectPublic(ResultActions result, int expectedStatus) throws Exception {
        expectSensitive(result, expectedStatus);
    }

    private void expectSensitive(
            MockHttpServletRequestBuilder request,
            int expectedStatus
    ) throws Exception {
        expectSensitive(mockMvc.perform(request), expectedStatus);
    }

    private void expectSensitive(ResultActions result, int expectedStatus) throws Exception {
        result.andExpect(status().is(expectedStatus))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    private void assertAuthenticationRequired(MockHttpServletRequestBuilder request) throws Exception {
        int responseStatus = mockMvc.perform(request).andReturn().getResponse().getStatus();
        assertThat(responseStatus).isIn(401, 403);
    }

    private MockHttpServletRequestBuilder json(
            MockHttpServletRequestBuilder request,
            String body
    ) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String invitationPath() {
        return invitationPath("");
    }

    private String invitationPath(String suffix) {
        return "/waybills/invitations/" + INVITATION_TOKEN + suffix;
    }
}
