package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.WaybillPayerClaimRequest;
import com.example.shopsecure.entity.OtpChallenge;
import com.example.shopsecure.entity.OtpPurpose;
import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillInvitation;
import com.example.shopsecure.entity.WaybillInvitationKind;
import com.example.shopsecure.entity.WaybillParty;
import com.example.shopsecure.entity.WaybillPartyRole;
import com.example.shopsecure.entity.WaybillPartySource;
import com.example.shopsecure.entity.WaybillPayerType;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.OtpVerificationException;
import com.example.shopsecure.repository.WaybillInvitationRepository;
import com.example.shopsecure.repository.WaybillParcelRepository;
import com.example.shopsecure.repository.WaybillPartyRepository;
import com.example.shopsecure.repository.WaybillRepository;
import com.example.shopsecure.repository.WaybillShipmentRepository;
import com.example.shopsecure.repository.WaybillShippingQuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaybillAccessServiceTest {

    private static final String RAW_INVITATION = "raw-invitation-token";
    private static final String RAW_ACCESS = "raw-access-token";

    private WaybillInvitationRepository invitationRepository;
    private WaybillPartyRepository partyRepository;
    private WaybillParcelRepository parcelRepository;
    private WaybillShipmentRepository shipmentRepository;
    private WaybillShippingQuoteRepository quoteRepository;
    private WaybillRepository waybillRepository;
    private OtpService otpService;
    private OtpDeliveryService otpDeliveryService;
    private WaybillStatusService statusService;
    private WaybillAccessService service;

    @BeforeEach
    void setUp() {
        invitationRepository = mock(WaybillInvitationRepository.class);
        partyRepository = mock(WaybillPartyRepository.class);
        parcelRepository = mock(WaybillParcelRepository.class);
        shipmentRepository = mock(WaybillShipmentRepository.class);
        quoteRepository = mock(WaybillShippingQuoteRepository.class);
        waybillRepository = mock(WaybillRepository.class);
        otpService = mock(OtpService.class);
        otpDeliveryService = mock(OtpDeliveryService.class);
        statusService = mock(WaybillStatusService.class);
        service = new WaybillAccessService(
                invitationRepository,
                partyRepository,
                parcelRepository,
                shipmentRepository,
                quoteRepository,
                waybillRepository,
                otpService,
                otpDeliveryService,
                statusService
        );
        ReflectionTestUtils.setField(service, "maxOtpSends", 5);
        ReflectionTestUtils.setField(service, "maxOtpFailedAttempts", 10);
        when(invitationRepository.save(any(WaybillInvitation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(parcelRepository.findByWaybill_Id(any())).thenReturn(Optional.empty());
        when(shipmentRepository.findByWaybill_Id(any())).thenReturn(Optional.empty());
        when(quoteRepository.findByShipment_Waybill_IdAndStatus(any(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void paymentOnlyLinkUsesStoredPayerContactAndExposesOnlyMaskedParties() throws Exception {
        WaybillInvitation invitation = paymentInvitation();
        stubInvitation(invitation);
        List<WaybillParty> sensitiveParties = sensitiveParties(invitation.getWaybill());
        when(partyRepository.findByWaybill_IdOrderByRoleAsc(10L)).thenReturn(sensitiveParties);
        when(partyRepository.findByWaybill_IdAndRole(10L, WaybillPartyRole.RECIPIENT))
                .thenReturn(Optional.of(sensitiveParties.get(1)));
        when(partyRepository.findByWaybillIdAndRoleForUpdate(10L, WaybillPartyRole.RECIPIENT))
                .thenReturn(Optional.of(sensitiveParties.get(1)));
        when(otpService.issue(
                OtpPurpose.WAYBILL_INVITATION,
                "waybill-invitation:20",
                "+2348012345678",
                "payer@example.com"
        )).thenReturn(issued("initial-challenge"));

        var publicInvitation = service.getInvitation(RAW_INVITATION);
        assertThat(publicInvitation.targetPartyRole()).isEqualTo(WaybillPartyRole.RECIPIENT);
        assertThat(publicInvitation.inviteeEmailMasked()).isEqualTo("p***@***");
        assertThat(publicInvitation.inviteePhoneNumberMasked()).endsWith("78");

        var challenge = service.requestPaymentAccess(RAW_INVITATION);
        assertThat(invitation.getStagedEmail()).isEqualTo("payer@example.com");
        assertThat(invitation.getStagedPhoneNumber()).isEqualTo("+2348012345678");
        assertThat(invitation.getOtpSendCount()).isEqualTo(1);
        assertThat(challenge.partyRole()).isEqualTo(WaybillPartyRole.RECIPIENT);

        when(otpService.requireChallenge(
                "initial-challenge",
                OtpPurpose.WAYBILL_INVITATION,
                "waybill-invitation:20"
        )).thenReturn(challenge(
                "initial-challenge",
                "waybill-invitation:20",
                "+2348012345678",
                "payer@example.com"
        ));
        var access = service.verify(RAW_INVITATION, "initial-challenge", "123456");

        assertThat(access.accessToken()).isNotBlank();
        assertThat(access.targetPartyRole()).isEqualTo(WaybillPartyRole.RECIPIENT);
        assertThat(access.parties()).hasSize(2);
        assertThat(access.canPay()).isTrue();
        assertThat(sensitiveParties.get(1).getContactVerifiedAt()).isNotNull();
        verify(otpService).verify(
                OtpPurpose.WAYBILL_INVITATION,
                "waybill-invitation:20",
                "initial-challenge",
                "123456"
        );
    }

    @Test
    void verifiedRecoveryUsesExistingContactAndRotatesAccessToken() throws Exception {
        WaybillInvitation invitation = paymentInvitation();
        invitation.setStagedFirstName("Link");
        invitation.setStagedLastName("Holder");
        invitation.setStagedEmail("payer@example.com");
        invitation.setStagedPhoneNumber("+2348012345678");
        invitation.setVerifiedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        invitation.setAccessTokenHash(sha256("old-access-token"));
        invitation.setOtpSendCount(1);
        stubInvitation(invitation);
        when(partyRepository.findByWaybill_IdOrderByRoleAsc(10L)).thenReturn(List.of());
        when(otpService.issue(
                OtpPurpose.WAYBILL_INVITATION,
                "waybill-invitation-recovery:20",
                "+2348012345678",
                "payer@example.com"
        )).thenReturn(issued("recovery-challenge"));

        assertThatThrownBy(() -> service.verify(
                RAW_INVITATION,
                "old-initial-challenge",
                "123456"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("Waybill invitation has already been verified");

        service.requestAccessRecovery(RAW_INVITATION);
        assertThat(invitation.getStagedEmail()).isEqualTo("payer@example.com");
        assertThat(invitation.getStagedPhoneNumber()).isEqualTo("+2348012345678");
        assertThat(invitation.getOtpSendCount()).isEqualTo(2);

        when(otpService.requireChallenge(
                "recovery-challenge",
                OtpPurpose.WAYBILL_INVITATION,
                "waybill-invitation-recovery:20"
        )).thenReturn(challenge(
                "recovery-challenge",
                "waybill-invitation-recovery:20",
                "+2348012345678",
                "payer@example.com"
        ));
        var recovered = service.verifyAccessRecovery(
                RAW_INVITATION,
                "recovery-challenge",
                "654321"
        );

        assertThat(recovered.accessToken()).isNotBlank();
        assertThat(invitation.getAccessTokenHash()).isEqualTo(sha256(recovered.accessToken()));
        assertThat(invitation.getAccessTokenHash()).isNotEqualTo(sha256("old-access-token"));
        assertThatThrownBy(() -> service.getAccessStatus(RAW_INVITATION, "old-access-token"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(service.getAccessStatus(RAW_INVITATION, recovered.accessToken()))
                .isNotNull();
        verify(otpService).verify(
                OtpPurpose.WAYBILL_INVITATION,
                "waybill-invitation-recovery:20",
                "recovery-challenge",
                "654321"
        );
    }

    @Test
    void cumulativeSendLimitCountsInitialAndResendThenLocks() throws Exception {
        ReflectionTestUtils.setField(service, "maxOtpSends", 2);
        WaybillInvitation invitation = paymentInvitation();
        stubInvitation(invitation);
        when(otpService.issue(any(), any(), any(), any()))
                .thenReturn(issued("challenge-1"), issued("challenge-2"));
        when(otpService.requireChallenge(
                "challenge-1",
                OtpPurpose.WAYBILL_INVITATION,
                "waybill-invitation:20"
        )).thenReturn(challenge(
                "challenge-1",
                "waybill-invitation:20",
                "+2348012345678",
                "payer@example.com"
        ));

        service.requestPaymentAccess(RAW_INVITATION);
        service.resendOtp(RAW_INVITATION, "challenge-1");

        assertThat(invitation.getOtpSendCount()).isEqualTo(2);
        assertThatThrownBy(() -> service.resendOtp(RAW_INVITATION, "challenge-2"))
                .isInstanceOf(OtpVerificationException.class)
                .hasMessage("Waybill invitation verification is locked");
        assertThat(invitation.getOtpLockedAt()).isNotNull();
        verify(otpService, times(2)).issue(any(), any(), any(), any());
        verify(invitationRepository, atLeastOnce()).save(invitation);
    }

    @Test
    void cumulativeVerificationFailuresPersistAndLockAcrossAttempts() throws Exception {
        ReflectionTestUtils.setField(service, "maxOtpFailedAttempts", 2);
        WaybillInvitation invitation = paymentInvitation();
        invitation.setStagedEmail("payer@example.com");
        invitation.setStagedPhoneNumber("+2348012345678");
        invitation.setOtpChallengeId("challenge-1");
        stubInvitation(invitation);
        when(partyRepository.findByWaybillIdAndRoleForUpdate(10L, WaybillPartyRole.RECIPIENT))
                .thenReturn(Optional.of(sensitiveParties(invitation.getWaybill()).get(1)));
        when(otpService.requireChallenge(
                "challenge-1",
                OtpPurpose.WAYBILL_INVITATION,
                "waybill-invitation:20"
        )).thenReturn(challenge(
                "challenge-1",
                "waybill-invitation:20",
                "+2348012345678",
                "payer@example.com"
        ));
        doThrow(new OtpVerificationException("Invalid or expired verification code"))
                .when(otpService).verify(
                        OtpPurpose.WAYBILL_INVITATION,
                        "waybill-invitation:20",
                        "challenge-1",
                        "bad-code"
                );

        assertThatThrownBy(() -> service.verify(RAW_INVITATION, "challenge-1", "bad-code"))
                .isInstanceOf(OtpVerificationException.class);
        assertThat(invitation.getOtpFailedAttemptCount()).isEqualTo(1);
        assertThat(invitation.getOtpLockedAt()).isNull();

        assertThatThrownBy(() -> service.verify(RAW_INVITATION, "challenge-1", "bad-code"))
                .isInstanceOf(OtpVerificationException.class);
        assertThat(invitation.getOtpFailedAttemptCount()).isEqualTo(2);
        assertThat(invitation.getOtpLockedAt()).isNotNull();

        assertThatThrownBy(() -> service.verify(RAW_INVITATION, "challenge-1", "bad-code"))
                .isInstanceOf(OtpVerificationException.class)
                .hasMessage("Waybill invitation verification is locked");
        verify(otpService, times(2)).verify(any(), any(), any(), any());
        verify(invitationRepository, atLeastOnce()).save(invitation);
    }

    @Test
    void challengeMustMatchBothNormalizedPhoneAndEmail() throws Exception {
        WaybillInvitation invitation = paymentInvitation();
        invitation.setStagedEmail("payer@example.com");
        invitation.setStagedPhoneNumber("+2348012345678");
        invitation.setOtpChallengeId("challenge-1");
        stubInvitation(invitation);
        when(otpService.requireChallenge(
                "challenge-1",
                OtpPurpose.WAYBILL_INVITATION,
                "waybill-invitation:20"
        )).thenReturn(challenge(
                "challenge-1",
                "waybill-invitation:20",
                "+2348012345678",
                "different@example.com"
        ));

        assertThatThrownBy(() -> service.verify(RAW_INVITATION, "challenge-1", "123456"))
                .isInstanceOf(OtpVerificationException.class)
                .hasMessage("Verification challenge does not match current contact details");
        assertThat(invitation.getOtpFailedAttemptCount()).isEqualTo(1);
        verify(otpService, never()).verify(any(), any(), any(), any());
    }

    @Test
    void recipientQuoteAccessRequiresCompletedRoleBoundPayingInvitation() throws Exception {
        WaybillInvitation invitation = recipientInvitation();
        WaybillParty recipient = verifiedRecipient(invitation.getWaybill());
        stubInvitation(invitation);
        when(partyRepository.findByWaybillIdAndRoleForUpdate(
                invitation.getWaybill().getId(),
                WaybillPartyRole.RECIPIENT
        )).thenReturn(Optional.of(recipient));

        assertThat(service.requireRecipientQuoteAccess(RAW_INVITATION, RAW_ACCESS))
                .isSameAs(invitation);
        assertThatThrownBy(() -> service.requireRecipientQuoteAccess(
                RAW_INVITATION,
                "wrong-access"
        )).isInstanceOf(AccessDeniedException.class);

        invitation.setCanPay(false);
        assertThatThrownBy(() -> service.requireRecipientQuoteAccess(RAW_INVITATION, RAW_ACCESS))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Waybill access does not permit recipient quote management");

        invitation.setCanPay(true);
        invitation.setDetailsCompletedAt(null);
        assertThatThrownBy(() -> service.requireRecipientQuoteAccess(RAW_INVITATION, RAW_ACCESS))
                .isInstanceOf(AccessDeniedException.class);

        invitation.setDetailsCompletedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        invitation.setKind(WaybillInvitationKind.PAYMENT_ONLY);
        invitation.setTargetPartyRole(null);
        assertThatThrownBy(() -> service.requireRecipientQuoteAccess(RAW_INVITATION, RAW_ACCESS))
                .isInstanceOf(AccessDeniedException.class);
    }

    private void stubInvitation(WaybillInvitation invitation) throws Exception {
        String tokenHash = sha256(RAW_INVITATION);
        invitation.setTokenHash(tokenHash);
        when(waybillRepository.findByIdForUpdate(invitation.getWaybill().getId()))
                .thenReturn(Optional.of(invitation.getWaybill()));
        when(invitationRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(invitation));
        when(invitationRepository.findByTokenHashForUpdate(tokenHash))
                .thenReturn(Optional.of(invitation));
    }

    private WaybillInvitation paymentInvitation() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Waybill waybill = Waybill.builder()
                .id(10L)
                .publicId("waybill-public-id")
                .creatorRole(WaybillPartyRole.SENDER)
                .payerType(WaybillPayerType.INVITED_PAYER)
                .description("Sensitive delivery")
                .declaredValue(new BigDecimal("25000.00"))
                .currency("NGN")
                .status(WaybillStatus.AWAITING_PAYMENT)
                .paymentStatus(WaybillPaymentStatus.PENDING)
                .totalAmount(new BigDecimal("30000.00"))
                .paymentDueAt(now.plusMinutes(15))
                .createdAt(now.minusHours(1))
                .updatedAt(now.minusHours(1))
                .build();
        return WaybillInvitation.builder()
                .id(20L)
                .waybill(waybill)
                .kind(WaybillInvitationKind.PAYMENT_ONLY)
                .targetPartyRole(null)
                .canPay(true)
                .tokenHash(sha256(RAW_INVITATION))
                .stagedFirstName("Paying")
                .stagedLastName("Recipient")
                .stagedEmail("payer@example.com")
                .stagedPhoneNumber("+2348012345678")
                .detailsCompletedAt(now.minusMinutes(5))
                .expiresAt(now.plusDays(1))
                .createdAt(now.minusHours(1))
                .updatedAt(now.minusHours(1))
                .build();
    }

    private WaybillInvitation recipientInvitation() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Waybill waybill = Waybill.builder()
                .id(10L)
                .publicId("waybill-public-id")
                .creatorRole(WaybillPartyRole.SENDER)
                .payerType(WaybillPayerType.INVITED_PAYER)
                .description("Recipient-paid delivery")
                .declaredValue(new BigDecimal("25000.00"))
                .currency("NGN")
                .status(WaybillStatus.AWAITING_QUOTE)
                .paymentStatus(WaybillPaymentStatus.NOT_STARTED)
                .createdAt(now.minusHours(1))
                .updatedAt(now.minusHours(1))
                .build();
        return WaybillInvitation.builder()
                .id(20L)
                .waybill(waybill)
                .kind(WaybillInvitationKind.PARTY_COMPLETION)
                .targetPartyRole(WaybillPartyRole.RECIPIENT)
                .canPay(true)
                .tokenHash(sha256(RAW_INVITATION))
                .accessTokenHash(sha256(RAW_ACCESS))
                .verifiedAt(now.minusMinutes(2))
                .detailsCompletedAt(now.minusMinutes(2))
                .expiresAt(now.plusDays(1))
                .createdAt(now.minusHours(1))
                .updatedAt(now.minusMinutes(2))
                .build();
    }

    private WaybillParty verifiedRecipient(Waybill waybill) {
        LocalDateTime completedAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(2);
        return WaybillParty.builder()
                .waybill(waybill)
                .role(WaybillPartyRole.RECIPIENT)
                .source(WaybillPartySource.INVITEE_PROVIDED)
                .firstName("Verified")
                .lastName("Recipient")
                .email("recipient@example.com")
                .phoneNumber("+2348012345678")
                .addressLine1("2 Receiver Road")
                .city("Abuja")
                .state("FCT")
                .country("NG")
                .postalCode("900001")
                .detailsCompletedAt(completedAt)
                .contactVerifiedAt(completedAt)
                .build();
    }

    private List<WaybillParty> sensitiveParties(Waybill waybill) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return List.of(
                WaybillParty.builder()
                        .waybill(waybill)
                        .role(WaybillPartyRole.SENDER)
                        .source(WaybillPartySource.ACCOUNT_SNAPSHOT)
                        .firstName("Secret")
                        .lastName("Sender")
                        .email("sender.private@example.com")
                        .phoneNumber("+2348099999999")
                        .city("Lagos")
                        .state("Lagos")
                        .country("NG")
                        .detailsCompletedAt(now)
                        .build(),
                WaybillParty.builder()
                        .waybill(waybill)
                        .role(WaybillPartyRole.RECIPIENT)
                        .source(WaybillPartySource.CREATOR_PROVIDED)
                        .firstName("Paying")
                        .lastName("Recipient")
                        .email("payer@example.com")
                        .phoneNumber("+2348012345678")
                        .city("Abuja")
                        .state("FCT")
                        .country("NG")
                        .detailsCompletedAt(now)
                        .build()
        );
    }

    private WaybillPayerClaimRequest payerClaim() {
        return new WaybillPayerClaimRequest(
                "Link",
                "Holder",
                "payer@example.com",
                "08012345678"
        );
    }

    private OtpService.IssuedOtp issued(String challengeId) {
        return new OtpService.IssuedOtp(
                challengeId,
                "123456",
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5)
        );
    }

    private OtpChallenge challenge(
            String challengeId,
            String subject,
            String phone,
            String email
    ) {
        return OtpChallenge.builder()
                .publicId(challengeId)
                .purpose(OtpPurpose.WAYBILL_INVITATION)
                .subject(subject)
                .phoneNumber(phone)
                .email(email)
                .build();
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                value.getBytes(StandardCharsets.UTF_8)
        ));
    }
}
