package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.OrderAddressRequest;
import com.example.shopsecure.dto.request.WaybillPartyRequest;
import com.example.shopsecure.dto.request.WaybillPayerClaimRequest;
import com.example.shopsecure.dto.response.PublicWaybillInvitationResponse;
import com.example.shopsecure.dto.response.WaybillAccessResponse;
import com.example.shopsecure.dto.response.WaybillOtpChallengeResponse;
import com.example.shopsecure.dto.response.WaybillParcelResponse;
import com.example.shopsecure.dto.response.WaybillPricingResponse;
import com.example.shopsecure.dto.response.WaybillPublicPartyResponse;
import com.example.shopsecure.dto.response.WaybillQuoteResponse;
import com.example.shopsecure.dto.response.WaybillShipmentResponse;
import com.example.shopsecure.entity.OtpChallenge;
import com.example.shopsecure.entity.OtpPurpose;
import com.example.shopsecure.entity.ShippingQuoteStatus;
import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillInvitation;
import com.example.shopsecure.entity.WaybillInvitationKind;
import com.example.shopsecure.entity.WaybillParcel;
import com.example.shopsecure.entity.WaybillParty;
import com.example.shopsecure.entity.WaybillPartyRole;
import com.example.shopsecure.entity.WaybillPartySource;
import com.example.shopsecure.entity.WaybillPayerType;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillShipment;
import com.example.shopsecure.entity.WaybillShippingQuote;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.OtpVerificationException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.WaybillInvitationRepository;
import com.example.shopsecure.repository.WaybillParcelRepository;
import com.example.shopsecure.repository.WaybillPartyRepository;
import com.example.shopsecure.repository.WaybillRepository;
import com.example.shopsecure.repository.WaybillShipmentRepository;
import com.example.shopsecure.repository.WaybillShippingQuoteRepository;
import com.example.shopsecure.util.NigerianPhoneNumber;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class WaybillAccessService {

    private static final int DEFAULT_MAX_OTP_SENDS = 5;
    private static final int DEFAULT_MAX_OTP_FAILURES = 10;
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@"
                    + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$"
    );

    private final WaybillInvitationRepository invitationRepository;
    private final WaybillPartyRepository partyRepository;
    private final WaybillParcelRepository parcelRepository;
    private final WaybillShipmentRepository shipmentRepository;
    private final WaybillShippingQuoteRepository quoteRepository;
    private final WaybillRepository waybillRepository;
    private final OtpService otpService;
    private final OtpDeliveryService otpDeliveryService;
    private final WaybillStatusService statusService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    @Value("${waybill.invitation-ttl:PT168H}")
    private Duration invitationTtl;

    @Value("${waybill.otp.max-sends:5}")
    private int maxOtpSends;

    @Value("${waybill.otp.max-failed-attempts:10}")
    private int maxOtpFailedAttempts;

    @Transactional(readOnly = true)
    public PublicWaybillInvitationResponse getInvitation(String rawToken) {
        WaybillInvitation invitation = requireActiveInvitation(rawToken, false);
        Waybill waybill = invitation.getWaybill();
        WaybillPartyRole paymentRole = targetPartyRole(invitation);
        WaybillParty targetParty = paymentRole == null
                ? null
                : partyRepository.findByWaybill_IdAndRole(
                        waybill.getId(),
                        paymentRole
                ).orElse(null);
        String email = invitation.getKind() == WaybillInvitationKind.PAYMENT_ONLY
                ? invitation.getStagedEmail()
                : hasText(invitation.getStagedEmail())
                ? invitation.getStagedEmail()
                : targetParty == null ? null : targetParty.getEmail();
        String phone = invitation.getKind() == WaybillInvitationKind.PAYMENT_ONLY
                ? invitation.getStagedPhoneNumber()
                : hasText(invitation.getStagedPhoneNumber())
                ? invitation.getStagedPhoneNumber()
                : targetParty == null ? null : targetParty.getPhoneNumber();

        return new PublicWaybillInvitationResponse(
                waybill.getPublicId(),
                invitation.getKind(),
                paymentRole,
                waybill.getCreatorRole(),
                payerRole(waybill),
                waybill.getDescription(),
                waybill.getDeclaredValue(),
                waybill.getCurrency(),
                waybill.getStatus(),
                waybill.getPaymentStatus(),
                waybill.getTotalAmount(),
                canPay(invitation),
                false,
                maskEmail(email),
                maskPhone(phone),
                invitation.getExpiresAt()
        );
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public WaybillOtpChallengeResponse requestPaymentAccess(String rawToken) {
        WaybillInvitation invitation = requireActiveInvitation(rawToken, true);
        requireUnverified(invitation);
        requireOtpSendAvailable(invitation);
        if (invitation.getKind() != WaybillInvitationKind.PAYMENT_ONLY
                || !canPay(invitation)
                || invitation.getPaymentCompletedAt() != null
                || invitation.getWaybill().getStatus() != WaybillStatus.AWAITING_PAYMENT) {
            throw new BusinessException("This payment link is not awaiting payment");
        }
        requireStagedContact(invitation);
        return issueOtp(invitation, invitationSubject(invitation));
    }

    @Transactional
    public IssuedPaymentLink issuePaymentLink(Waybill waybill) {
        if (waybill.getPayerType() == WaybillPayerType.CREATOR) {
            return null;
        }
        if (waybill.getStatus() != WaybillStatus.AWAITING_PAYMENT) {
            throw new BusinessException("Waybill is not ready for payer checkout");
        }
        if (waybill.getPaymentStatus() != WaybillPaymentStatus.PENDING) {
            throw new BusinessException("Waybill is not ready for payer checkout");
        }
        if (invitationTtl == null || invitationTtl.isZero() || invitationTtl.isNegative()) {
            throw new IllegalStateException("waybill.invitation-ttl must be positive");
        }

        LocalDateTime now = utcNow();
        invitationRepository.findActiveByWaybillIdForUpdate(waybill.getId(), now)
                .forEach(invitation -> {
                    invitation.setCanPay(false);
                    invitation.setAccessTokenHash(null);
                    invitation.setRevokedAt(now);
                    invitation.setUpdatedAt(now);
                    invitationRepository.save(invitation);
                });
        WaybillParty payer = partyRepository.findByWaybill_IdAndRole(waybill.getId(), payerRole(waybill))
                .orElseThrow(() -> new BusinessException("Waybill payer details are not available"));
        String rawToken = randomToken();
        LocalDateTime expiresAt = now.plus(invitationTtl);
        if (waybill.getPaymentDueAt() == null || !waybill.getPaymentDueAt().isAfter(now)) {
            throw new BusinessException("Waybill payment window has expired");
        }
        if (waybill.getPaymentDueAt().isBefore(expiresAt)) {
            expiresAt = waybill.getPaymentDueAt();
        }
        invitationRepository.save(WaybillInvitation.builder()
                .waybill(waybill)
                .kind(WaybillInvitationKind.PAYMENT_ONLY)
                .canPay(true)
                .tokenHash(hash(rawToken))
                .stagedFirstName(payer.getFirstName())
                .stagedLastName(payer.getLastName())
                .stagedEmail(payer.getEmail())
                .stagedPhoneNumber(payer.getPhoneNumber())
                .detailsCompletedAt(payer.getDetailsCompletedAt())
                .expiresAt(expiresAt)
                .createdAt(now)
                .updatedAt(now)
                .build());
        String baseUrl = frontendBaseUrl == null ? "" : frontendBaseUrl.replaceAll("/+$", "");
        return new IssuedPaymentLink(
                baseUrl + "/waybills/invitations/" + rawToken,
                expiresAt
        );
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public WaybillOtpChallengeResponse submitPartyDetails(
            String rawToken,
            WaybillPartyRequest request
    ) {
        WaybillInvitation invitation = requireActiveInvitation(rawToken, true);
        requireUnverified(invitation);
        requireOtpSendAvailable(invitation);
        if (invitation.getKind() != WaybillInvitationKind.PARTY_COMPLETION) {
            throw new BusinessException("This invitation does not accept party details");
        }
        if (invitation.getDetailsCompletedAt() != null) {
            throw new BusinessException("Party details have already been completed");
        }
        Waybill waybill = invitation.getWaybill();
        if (waybill.getStatus() != WaybillStatus.AWAITING_PARTY_DETAILS) {
            throw new BusinessException("Waybill is no longer awaiting party details");
        }
        WaybillPartyRole targetRole = invitation.getTargetPartyRole();
        if (targetRole == null || targetRole != waybill.getCreatorRole().opposite()) {
            throw new BusinessException("Invitation target party is invalid");
        }

        NormalizedParty normalized = normalizeParty(request);
        LocalDateTime now = utcNow();
        WaybillParty party = partyRepository.findByWaybillIdAndRoleForUpdate(
                waybill.getId(),
                targetRole
        ).orElseGet(() -> WaybillParty.builder()
                .waybill(waybill)
                .role(targetRole)
                .createdAt(now)
                .build());
        if (party.getDetailsCompletedAt() != null) {
            throw new BusinessException("Party details have already been completed");
        }

        party.setUser(null);
        party.setSource(WaybillPartySource.INVITEE_PROVIDED);
        party.setFirstName(normalized.firstName());
        party.setLastName(normalized.lastName());
        party.setEmail(normalized.email());
        party.setPhoneNumber(normalized.phoneNumber());
        party.setAddressLine1(normalized.address().line1());
        party.setAddressLine2(normalized.address().line2());
        party.setCity(normalized.address().city());
        party.setState(normalized.address().state());
        party.setCountry(normalized.address().country());
        party.setPostalCode(normalized.address().postalCode());
        party.setDetailsCompletedAt(null);
        party.setContactVerifiedAt(null);
        party.setUpdatedAt(now);
        partyRepository.save(party);

        stageContact(
                invitation,
                normalized.firstName(),
                normalized.lastName(),
                normalized.email(),
                normalized.phoneNumber()
        );
        return issueOtp(invitation, invitationSubject(invitation));
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public WaybillOtpChallengeResponse claimPayer(
            String rawToken,
            WaybillPayerClaimRequest request
    ) {
        WaybillInvitation invitation = requireActiveInvitation(rawToken, true);
        requireUnverified(invitation);
        requireOtpSendAvailable(invitation);
        if (invitation.getKind() != WaybillInvitationKind.PAYMENT_ONLY) {
            throw new BusinessException("This invitation requires party details");
        }
        if (!canPay(invitation) || invitation.getPaymentCompletedAt() != null) {
            throw new BusinessException("This invitation cannot be used to pay for the waybill");
        }

        NormalizedPayer normalized = normalizePayer(request);
        stageContact(
                invitation,
                normalized.firstName(),
                normalized.lastName(),
                normalized.email(),
                normalized.phoneNumber()
        );
        return issueOtp(invitation, invitationSubject(invitation));
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public WaybillAccessResponse verify(String rawToken, String challengeId, String code) {
        WaybillInvitation invitation = requireActiveInvitation(rawToken, true);
        requireUnverified(invitation);
        requireStagedContact(invitation);
        requireOtpVerificationAvailable(invitation);

        Waybill waybill = invitation.getWaybill();
        WaybillParty party;
        try {
            verifyChallengeBinding(invitation, challengeId, invitationSubject(invitation));
            if (code == null || code.isBlank()) {
                throw new OtpVerificationException("Invalid or expired verification code");
            }
            if (invitation.getKind() == WaybillInvitationKind.PARTY_COMPLETION) {
                party = requireCompletableParty(invitation, waybill);
            } else if (!canPay(invitation) || invitation.getPaymentCompletedAt() != null) {
                throw new BusinessException("This invitation cannot be used to pay for the waybill");
            } else {
                party = requirePaymentParty(invitation, waybill);
            }

            otpService.verify(
                    OtpPurpose.WAYBILL_INVITATION,
                    invitationSubject(invitation),
                    challengeId,
                    code
            );
        } catch (OtpVerificationException exception) {
            recordOtpFailure(invitation);
            throw exception;
        }

        LocalDateTime now = utcNow();
        if (party != null) {
            if (invitation.getKind() == WaybillInvitationKind.PARTY_COMPLETION) {
                completeParty(invitation, waybill, party, now);
            } else {
                party.setContactVerifiedAt(now);
                party.setUpdatedAt(now);
                partyRepository.save(party);
            }
        }

        String rawAccessToken = randomToken();
        invitation.setVerifiedAt(now);
        invitation.setAccessTokenHash(hash(rawAccessToken));
        invitation.setUpdatedAt(now);
        invitationRepository.save(invitation);
        return accessResponse(invitation, rawAccessToken);
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public WaybillOtpChallengeResponse resendOtp(String rawToken, String challengeId) {
        WaybillInvitation invitation = requireActiveInvitation(rawToken, true);
        requireUnverified(invitation);
        requireStagedContact(invitation);
        requireOtpSendAvailable(invitation);
        verifyChallengeBinding(invitation, challengeId, invitationSubject(invitation));
        return issueOtp(invitation, invitationSubject(invitation));
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public WaybillOtpChallengeResponse requestAccessRecovery(String rawToken) {
        WaybillInvitation invitation = requireActiveInvitation(rawToken, true);
        requireVerified(invitation);
        requireStagedContact(invitation);
        requireOtpSendAvailable(invitation);
        return issueOtp(invitation, recoverySubject(invitation));
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public WaybillAccessResponse verifyAccessRecovery(
            String rawToken,
            String challengeId,
            String code
    ) {
        WaybillInvitation invitation = requireActiveInvitation(rawToken, true);
        requireVerified(invitation);
        requireStagedContact(invitation);
        requireOtpVerificationAvailable(invitation);
        try {
            verifyChallengeBinding(invitation, challengeId, recoverySubject(invitation));
            if (code == null || code.isBlank()) {
                throw new OtpVerificationException("Invalid or expired verification code");
            }
            otpService.verify(
                    OtpPurpose.WAYBILL_INVITATION,
                    recoverySubject(invitation),
                    challengeId,
                    code
            );
        } catch (OtpVerificationException exception) {
            recordOtpFailure(invitation);
            throw exception;
        }

        String rawAccessToken = randomToken();
        invitation.setAccessTokenHash(hash(rawAccessToken));
        invitation.setUpdatedAt(utcNow());
        invitationRepository.save(invitation);
        return accessResponse(invitation, rawAccessToken);
    }

    @Transactional(readOnly = true)
    public WaybillAccessResponse getAccessStatus(String rawToken, String rawAccessToken) {
        WaybillInvitation invitation = requireAccess(rawToken, rawAccessToken, false);
        return accessResponse(invitation, null);
    }

    @Transactional
    public WaybillInvitation requirePayAccess(String rawInvitationToken, String rawAccessToken) {
        WaybillInvitation invitation = requireAccess(rawInvitationToken, rawAccessToken, true);
        if (!canPay(invitation) || invitation.getPaymentCompletedAt() != null) {
            throw new AccessDeniedException("Waybill access does not permit payment");
        }
        return invitation;
    }

    @Transactional
    public WaybillInvitation requireRecipientQuoteAccess(
            String rawInvitationToken,
            String rawAccessToken
    ) {
        WaybillInvitation invitation = requireAccess(rawInvitationToken, rawAccessToken, true);
        Waybill waybill = invitation.getWaybill();
        if (waybill.getPayerType() != WaybillPayerType.INVITED_PAYER
                || waybill.getCreatorRole() != WaybillPartyRole.SENDER
                || invitation.getKind() != WaybillInvitationKind.PARTY_COMPLETION
                || invitation.getTargetPartyRole() != WaybillPartyRole.RECIPIENT
                || !invitation.isCanPay()
                || invitation.getDetailsCompletedAt() == null
                || invitation.getPaymentCompletedAt() != null) {
            throw new AccessDeniedException("Waybill access does not permit recipient quote management");
        }
        WaybillParty recipient = partyRepository.findByWaybillIdAndRoleForUpdate(
                        waybill.getId(),
                        WaybillPartyRole.RECIPIENT
                )
                .orElseThrow(() -> new AccessDeniedException(
                        "Waybill access does not permit recipient quote management"
                ));
        if (recipient.getDetailsCompletedAt() == null || recipient.getContactVerifiedAt() == null) {
            throw new AccessDeniedException("Waybill recipient details are not verified");
        }
        return invitation;
    }

    @Transactional
    public void revokeAll(Waybill waybill) {
        if (waybill == null || waybill.getId() == null) {
            throw new BusinessException("Waybill is required when revoking invitations");
        }
        LocalDateTime now = utcNow();
        invitationRepository.findActiveByWaybillIdForUpdate(waybill.getId(), now)
                .forEach(invitation -> {
                    invitation.setCanPay(false);
                    invitation.setAccessTokenHash(null);
                    invitation.setRevokedAt(now);
                    invitation.setUpdatedAt(now);
                    invitationRepository.save(invitation);
                });
    }

    @Transactional
    public void markPaymentCompleted(WaybillInvitation invitation) {
        if (invitation == null || invitation.getId() == null) {
            throw new BusinessException("Waybill invitation is required");
        }
        WaybillInvitation managed = invitationRepository.findById(invitation.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Waybill invitation not found"));
        LocalDateTime now = utcNow();
        if (managed.getPaymentCompletedAt() == null) {
            managed.setPaymentCompletedAt(now);
        }
        managed.setCanPay(false);
        managed.setUpdatedAt(now);
        invitationRepository.save(managed);
    }

    private void completeParty(
            WaybillInvitation invitation,
            Waybill waybill,
            WaybillParty party,
            LocalDateTime now
    ) {
        party.setDetailsCompletedAt(now);
        party.setContactVerifiedAt(now);
        party.setUpdatedAt(now);
        partyRepository.save(party);
        invitation.setDetailsCompletedAt(now);
        statusService.transition(
                waybill,
                WaybillStatus.AWAITING_QUOTE,
                party.getRole().name().toLowerCase(Locale.ROOT) + " details verified"
        );
        waybillRepository.save(waybill);
    }

    private WaybillParty requireCompletableParty(
            WaybillInvitation invitation,
            Waybill waybill
    ) {
        if (waybill.getStatus() != WaybillStatus.AWAITING_PARTY_DETAILS) {
            throw new BusinessException("Waybill is no longer awaiting party details");
        }
        if (invitation.getDetailsCompletedAt() != null) {
            throw new BusinessException("Party details have already been completed");
        }
        WaybillPartyRole targetRole = invitation.getTargetPartyRole();
        if (targetRole == null || targetRole != waybill.getCreatorRole().opposite()) {
            throw new BusinessException("Invitation target party is invalid");
        }
        WaybillParty party = partyRepository.findByWaybillIdAndRoleForUpdate(
                waybill.getId(),
                targetRole
        ).orElseThrow(() -> new BusinessException("Party details must be submitted first"));
        if (party.getSource() != WaybillPartySource.INVITEE_PROVIDED
                || party.getDetailsCompletedAt() != null
                || !constantEquals(party.getEmail(), invitation.getStagedEmail())
                || !constantEquals(party.getPhoneNumber(), invitation.getStagedPhoneNumber())) {
            throw new OtpVerificationException("Verification challenge does not match current contact details");
        }
        return party;
    }

    private WaybillParty requirePaymentParty(
            WaybillInvitation invitation,
            Waybill waybill
    ) {
        if (waybill.getStatus() != WaybillStatus.AWAITING_PAYMENT
                || invitation.getKind() != WaybillInvitationKind.PAYMENT_ONLY) {
            throw new BusinessException("Waybill is no longer awaiting payer verification");
        }
        WaybillParty party = partyRepository.findByWaybillIdAndRoleForUpdate(
                        waybill.getId(),
                        payerRole(waybill)
                )
                .orElseThrow(() -> new BusinessException("Waybill payer details are unavailable"));
        if (party.getDetailsCompletedAt() == null
                || !constantEquals(party.getEmail(), invitation.getStagedEmail())
                || !constantEquals(party.getPhoneNumber(), invitation.getStagedPhoneNumber())) {
            throw new OtpVerificationException("Verification challenge does not match the selected payer");
        }
        return party;
    }

    private WaybillOtpChallengeResponse issueOtp(
            WaybillInvitation invitation,
            String subject
    ) {
        requireStagedContact(invitation);
        requireOtpSendAvailable(invitation);
        OtpService.IssuedOtp issued = otpService.issue(
                OtpPurpose.WAYBILL_INVITATION,
                subject,
                invitation.getStagedPhoneNumber(),
                invitation.getStagedEmail()
        );
        otpDeliveryService.send(
                invitation.getStagedPhoneNumber(),
                invitation.getStagedEmail(),
                issued.code(),
                OtpPurpose.WAYBILL_INVITATION
        );
        invitation.setOtpChallengeId(issued.challengeId());
        invitation.setOtpSendCount(invitation.getOtpSendCount() + 1);
        invitation.setUpdatedAt(utcNow());
        invitationRepository.save(invitation);
        return new WaybillOtpChallengeResponse(
                issued.challengeId(),
                targetPartyRole(invitation),
                maskEmail(invitation.getStagedEmail()),
                maskPhone(invitation.getStagedPhoneNumber()),
                issued.expiresInSeconds()
        );
    }

    private void verifyChallengeBinding(
            WaybillInvitation invitation,
            String challengeId,
            String subject
    ) {
        if (!hasText(challengeId)
                || !constantEquals(invitation.getOtpChallengeId(), challengeId)) {
            throw new OtpVerificationException("Invalid verification challenge");
        }
        OtpChallenge challenge = otpService.requireChallenge(
                challengeId,
                OtpPurpose.WAYBILL_INVITATION,
                subject
        );
        if (!constantEquals(challenge.getPhoneNumber(), invitation.getStagedPhoneNumber())
                || !constantEquals(challenge.getEmail(), invitation.getStagedEmail())) {
            throw new OtpVerificationException("Verification challenge does not match current contact details");
        }
    }

    private WaybillInvitation requireActiveInvitation(String rawToken, boolean forUpdate) {
        if (!hasText(rawToken)) {
            throw new ResourceNotFoundException("Waybill invitation not found");
        }
        String suppliedHash = hash(rawToken);
        WaybillInvitation invitation;
        if (forUpdate) {
            WaybillInvitation located = invitationRepository.findByTokenHash(suppliedHash)
                    .orElseThrow(() -> new ResourceNotFoundException("Waybill invitation not found"));
            Long waybillId = located.getWaybill().getId();
            waybillRepository.findByIdForUpdate(waybillId)
                    .orElseThrow(() -> new ResourceNotFoundException("Waybill invitation not found"));
            invitation = invitationRepository.findByTokenHashForUpdate(suppliedHash)
                    .filter(item -> item.getWaybill().getId().equals(waybillId))
                    .orElseThrow(() -> new ResourceNotFoundException("Waybill invitation not found"));
        } else {
            invitation = invitationRepository.findByTokenHash(suppliedHash)
                    .orElseThrow(() -> new ResourceNotFoundException("Waybill invitation not found"));
        }
        LocalDateTime now = utcNow();
        if (!constantEquals(invitation.getTokenHash(), suppliedHash)
                || invitation.getRevokedAt() != null
                || !invitation.getExpiresAt().isAfter(now)) {
            throw new ResourceNotFoundException("Waybill invitation not found");
        }
        return invitation;
    }

    private WaybillInvitation requireAccess(
            String rawInvitationToken,
            String rawAccessToken,
            boolean forUpdate
    ) {
        if (!hasText(rawInvitationToken) || !hasText(rawAccessToken)) {
            throw new AccessDeniedException("Verified waybill access is required");
        }
        String invitationHash = hash(rawInvitationToken);
        String accessHash = hash(rawAccessToken);
        WaybillInvitation invitation;
        if (forUpdate) {
            WaybillInvitation located = invitationRepository.findByTokenHash(invitationHash)
                    .orElseThrow(() -> new AccessDeniedException("Waybill access is invalid"));
            Long waybillId = located.getWaybill().getId();
            waybillRepository.findByIdForUpdate(waybillId)
                    .orElseThrow(() -> new AccessDeniedException("Waybill access is invalid"));
            invitation = invitationRepository.findByTokenHashForUpdate(invitationHash)
                    .filter(item -> item.getWaybill().getId().equals(waybillId))
                    .orElseThrow(() -> new AccessDeniedException("Waybill access is invalid"));
        } else {
            invitation = invitationRepository.findByTokenHash(invitationHash)
                    .orElseThrow(() -> new AccessDeniedException("Waybill access is invalid"));
        }
        LocalDateTime now = utcNow();
        if (!constantEquals(invitation.getTokenHash(), invitationHash)
                || !constantEquals(invitation.getAccessTokenHash(), accessHash)
                || invitation.getVerifiedAt() == null
                || invitation.getRevokedAt() != null
                || !invitation.getExpiresAt().isAfter(now)) {
            throw new AccessDeniedException("Waybill access is invalid or expired");
        }
        return invitation;
    }

    private void requireUnverified(WaybillInvitation invitation) {
        if (invitation.getVerifiedAt() != null || invitation.getAccessTokenHash() != null) {
            throw new BusinessException("Waybill invitation has already been verified");
        }
    }

    private void requireVerified(WaybillInvitation invitation) {
        if (invitation.getVerifiedAt() == null) {
            throw new BusinessException("Waybill invitation must be verified before recovering access");
        }
    }

    private void requireOtpSendAvailable(WaybillInvitation invitation) {
        requireOtpUnlocked(invitation);
        if (invitation.getOtpSendCount() >= configuredMaxOtpSends()) {
            lockOtp(invitation);
            throw new OtpVerificationException("Waybill invitation verification is locked");
        }
    }

    private void requireOtpVerificationAvailable(WaybillInvitation invitation) {
        requireOtpUnlocked(invitation);
        if (invitation.getOtpFailedAttemptCount() >= configuredMaxOtpFailures()) {
            lockOtp(invitation);
            throw new OtpVerificationException("Waybill invitation verification is locked");
        }
    }

    private void requireOtpUnlocked(WaybillInvitation invitation) {
        if (invitation.getOtpLockedAt() != null) {
            throw new OtpVerificationException("Waybill invitation verification is locked");
        }
    }

    private void recordOtpFailure(WaybillInvitation invitation) {
        int failures = invitation.getOtpFailedAttemptCount();
        if (failures < Integer.MAX_VALUE) {
            failures++;
        }
        invitation.setOtpFailedAttemptCount(failures);
        LocalDateTime now = utcNow();
        if (failures >= configuredMaxOtpFailures()) {
            invitation.setOtpLockedAt(now);
        }
        invitation.setUpdatedAt(now);
        invitationRepository.save(invitation);
    }

    private void lockOtp(WaybillInvitation invitation) {
        LocalDateTime now = utcNow();
        invitation.setOtpLockedAt(now);
        invitation.setUpdatedAt(now);
        invitationRepository.save(invitation);
    }

    private int configuredMaxOtpSends() {
        return maxOtpSends > 0 ? maxOtpSends : DEFAULT_MAX_OTP_SENDS;
    }

    private int configuredMaxOtpFailures() {
        return maxOtpFailedAttempts > 0
                ? maxOtpFailedAttempts
                : DEFAULT_MAX_OTP_FAILURES;
    }

    private void requireStagedContact(WaybillInvitation invitation) {
        if (!hasText(invitation.getStagedEmail()) || !hasText(invitation.getStagedPhoneNumber())) {
            throw new BusinessException("Contact details must be submitted first");
        }
    }

    private void stageContact(
            WaybillInvitation invitation,
            String firstName,
            String lastName,
            String email,
            String phoneNumber
    ) {
        invitation.setStagedFirstName(firstName);
        invitation.setStagedLastName(lastName);
        invitation.setStagedEmail(email);
        invitation.setStagedPhoneNumber(phoneNumber);
    }

    private boolean canPay(WaybillInvitation invitation) {
        Waybill waybill = invitation.getWaybill();
        LocalDateTime now = utcNow();
        return waybill.getPayerType() == WaybillPayerType.INVITED_PAYER
                && invitation.isCanPay()
                && waybill.getStatus() == WaybillStatus.AWAITING_PAYMENT
                && waybill.getPaymentStatus() == WaybillPaymentStatus.PENDING
                && waybill.getPaymentDueAt() != null
                && waybill.getPaymentDueAt().isAfter(now)
                && invitation.getExpiresAt().isAfter(now);
    }

    private String invitationSubject(WaybillInvitation invitation) {
        return "waybill-invitation:" + invitation.getId();
    }

    private String recoverySubject(WaybillInvitation invitation) {
        return "waybill-invitation-recovery:" + invitation.getId();
    }

    private WaybillPartyRole targetPartyRole(WaybillInvitation invitation) {
        return invitation.getKind() == WaybillInvitationKind.PAYMENT_ONLY
                ? payerRole(invitation.getWaybill())
                : invitation.getTargetPartyRole();
    }

    private WaybillAccessResponse accessResponse(
            WaybillInvitation invitation,
            String issuedAccessToken
    ) {
        Waybill waybill = invitation.getWaybill();
        WaybillParcel parcel = parcelRepository.findByWaybill_Id(waybill.getId()).orElse(null);
        WaybillShipment shipment = shipmentRepository.findByWaybill_Id(waybill.getId()).orElse(null);
        WaybillShippingQuote selectedQuote = selectedQuote(waybill.getId());
        List<WaybillParty> storedParties = partyRepository
                .findByWaybill_IdOrderByRoleAsc(waybill.getId());
        boolean detailsCompleted = storedParties.size() == WaybillPartyRole.values().length
                && storedParties.stream().allMatch(party -> party.getDetailsCompletedAt() != null);
        List<WaybillPublicPartyResponse> parties = storedParties.stream()
                .map(this::publicPartyResponse)
                .toList();

        return new WaybillAccessResponse(
                waybill.getPublicId(),
                issuedAccessToken,
                invitation.getKind(),
                targetPartyRole(invitation),
                false,
                canPay(invitation),
                detailsCompleted,
                invitation.getPaymentCompletedAt() != null,
                waybill.getStatus(),
                waybill.getPaymentStatus(),
                waybill.getDescription(),
                waybill.getDeclaredValue(),
                waybill.getCurrency(),
                pricing(waybill),
                parties,
                parcelResponse(parcel),
                quoteResponse(selectedQuote),
                shipmentResponse(shipment),
                invitation.getExpiresAt()
        );
    }

    private WaybillPublicPartyResponse publicPartyResponse(WaybillParty party) {
        return new WaybillPublicPartyResponse(
                party.getRole(),
                maskName(party.getFirstName(), party.getLastName()),
                maskEmail(party.getEmail()),
                maskPhone(party.getPhoneNumber()),
                party.getCity(),
                party.getState(),
                party.getCountry()
        );
    }

    private WaybillPricingResponse pricing(Waybill waybill) {
        if (waybill.getTotalAmount() == null) {
            return null;
        }
        return new WaybillPricingResponse(
                waybill.getDeliveryFee(),
                waybill.getServiceFee(),
                waybill.getTotalAmount(),
                waybill.getCurrency()
        );
    }

    private WaybillParcelResponse parcelResponse(WaybillParcel parcel) {
        return parcel == null ? null : new WaybillParcelResponse(
                parcel.getWeightKg(),
                parcel.getLengthCm(),
                parcel.getWidthCm(),
                parcel.getHeightCm(),
                parcel.getQuantity(),
                parcel.getParcelType()
        );
    }

    private WaybillShippingQuote selectedQuote(Long waybillId) {
        return quoteRepository.findByShipment_Waybill_IdAndStatus(waybillId, ShippingQuoteStatus.SELECTED)
                .orElseGet(() -> quoteRepository.findByShipment_Waybill_IdAndStatus(
                        waybillId,
                        ShippingQuoteStatus.USED
                ).orElse(null));
    }

    private WaybillQuoteResponse quoteResponse(WaybillShippingQuote quote) {
        return quote == null ? null : new WaybillQuoteResponse(
                quote.getId(),
                quote.getCarrierName(),
                quote.getCarrierLogo(),
                quote.getServiceDescription(),
                quote.getDeliveryFee(),
                quote.getServiceFee(),
                quote.getTotalAmount(),
                quote.getCurrency(),
                quote.getPickupEstimate(),
                quote.getDeliveryEstimate(),
                quote.getStatus(),
                quote.getExpiresAt()
        );
    }

    private WaybillShipmentResponse shipmentResponse(WaybillShipment shipment) {
        return shipment == null ? null : new WaybillShipmentResponse(
                shipment.getProvider(),
                shipment.getProviderShipmentId(),
                shipment.getStatus(),
                shipment.getTrackingReference(),
                shipment.getTrackingUrl(),
                shipment.getFailureReason(),
                shipment.getProviderUpdatedAt(),
                shipment.getUpdatedAt()
        );
    }

    private NormalizedParty normalizeParty(WaybillPartyRequest request) {
        if (request == null) {
            throw new BusinessException("Party details are required");
        }
        return new NormalizedParty(
                requiredText(request.firstName(), "First name", 100),
                requiredText(request.lastName(), "Last name", 100),
                normalizeEmail(request.email()),
                NigerianPhoneNumber.normalize(request.phoneNumber()),
                normalizeAddress(request.address())
        );
    }

    private NormalizedPayer normalizePayer(WaybillPayerClaimRequest request) {
        if (request == null) {
            throw new BusinessException("Payer details are required");
        }
        return new NormalizedPayer(
                requiredText(request.firstName(), "First name", 100),
                requiredText(request.lastName(), "Last name", 100),
                normalizeEmail(request.email()),
                NigerianPhoneNumber.normalize(request.phoneNumber())
        );
    }

    private NormalizedAddress normalizeAddress(OrderAddressRequest request) {
        if (request == null) {
            throw new BusinessException("Party address is required");
        }
        String country = requiredText(request.country(), "Country", 2).toUpperCase(Locale.ROOT);
        if (!"NG".equals(country)) {
            throw new BusinessException("Only Nigerian shipping addresses are supported");
        }
        return new NormalizedAddress(
                requiredText(request.line1(), "Address line 1", 255),
                optionalText(request.line2(), 255),
                requiredText(request.city(), "City", 100),
                requiredText(request.state(), "State", 100),
                country,
                requiredText(request.postalCode(), "Postal code", 32)
        );
    }

    private String requiredText(String value, String field, int maxLength) {
        if (!hasText(value)) {
            throw new BusinessException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private String optionalText(String value, int maxLength) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException("Optional address line must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private String normalizeEmail(String email) {
        String normalized = requiredText(email, "Email", 255).toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException("Email must be valid");
        }
        return normalized;
    }

    private String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    rawToken.getBytes(StandardCharsets.UTF_8)
            ));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to secure waybill token", exception);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean constantEquals(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8),
                second.getBytes(StandardCharsets.UTF_8)
        );
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private String maskName(String firstName, String lastName) {
        String first = maskWord(firstName);
        String last = maskWord(lastName);
        if (first.isEmpty()) {
            return last;
        }
        if (last.isEmpty()) {
            return first;
        }
        return first + " " + last;
    }

    private String maskWord(String value) {
        if (!hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() == 1) {
            return "*";
        }
        return normalized.substring(0, 1) + "*".repeat(normalized.length() - 1);
    }

    private String maskEmail(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        int at = normalized.indexOf('@');
        if (at <= 0) {
            return maskWord(normalized);
        }
        return normalized.substring(0, 1) + "***@***";
    }

    private String maskPhone(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() <= 2) {
            return "*".repeat(normalized.length());
        }
        return "*".repeat(normalized.length() - 2)
                + normalized.substring(normalized.length() - 2);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private WaybillPartyRole payerRole(Waybill waybill) {
        return waybill.getPayerType() == WaybillPayerType.CREATOR
                ? waybill.getCreatorRole()
                : waybill.getCreatorRole().opposite();
    }

    private record NormalizedParty(
            String firstName,
            String lastName,
            String email,
            String phoneNumber,
            NormalizedAddress address
    ) {
    }

    private record NormalizedPayer(
            String firstName,
            String lastName,
            String email,
            String phoneNumber
    ) {
    }

    private record NormalizedAddress(
            String line1,
            String line2,
            String city,
            String state,
            String country,
            String postalCode
    ) {
    }

    public record IssuedPaymentLink(String link, LocalDateTime expiresAt) {
    }
}
