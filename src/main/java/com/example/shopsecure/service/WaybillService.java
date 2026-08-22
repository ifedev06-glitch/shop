package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.CreateWaybillRequest;
import com.example.shopsecure.dto.request.OrderAddressRequest;
import com.example.shopsecure.dto.request.OrderParcelRequest;
import com.example.shopsecure.dto.request.WaybillPartyRequest;
import com.example.shopsecure.dto.response.AddressResponse;
import com.example.shopsecure.dto.response.CreateWaybillResponse;
import com.example.shopsecure.dto.response.PageResponse;
import com.example.shopsecure.dto.response.WaybillDetailsResponse;
import com.example.shopsecure.dto.response.WaybillInvitationReissueResponse;
import com.example.shopsecure.dto.response.WaybillInvitationSummaryResponse;
import com.example.shopsecure.dto.response.WaybillParcelResponse;
import com.example.shopsecure.dto.response.WaybillPartyResponse;
import com.example.shopsecure.dto.response.WaybillPricingResponse;
import com.example.shopsecure.dto.response.WaybillQuoteResponse;
import com.example.shopsecure.dto.response.WaybillShipmentResponse;
import com.example.shopsecure.dto.response.WaybillStatusHistoryResponse;
import com.example.shopsecure.entity.ShippingQuoteStatus;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillInvitation;
import com.example.shopsecure.entity.WaybillInvitationKind;
import com.example.shopsecure.entity.WaybillParcel;
import com.example.shopsecure.entity.WaybillParty;
import com.example.shopsecure.entity.WaybillPartyRole;
import com.example.shopsecure.entity.WaybillPartySource;
import com.example.shopsecure.entity.WaybillPayerType;
import com.example.shopsecure.entity.WaybillPaymentAttempt;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillShipment;
import com.example.shopsecure.entity.WaybillShippingQuote;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.UserRepository;
import com.example.shopsecure.repository.WaybillInvitationRepository;
import com.example.shopsecure.repository.WaybillParcelRepository;
import com.example.shopsecure.repository.WaybillPartyRepository;
import com.example.shopsecure.repository.WaybillPaymentAttemptRepository;
import com.example.shopsecure.repository.WaybillRepository;
import com.example.shopsecure.repository.WaybillShipmentRepository;
import com.example.shopsecure.repository.WaybillShippingQuoteRepository;
import com.example.shopsecure.repository.WaybillStatusHistoryRepository;
import com.example.shopsecure.util.NigerianPhoneNumber;
import com.example.shopsecure.util.Pagination;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class WaybillService {

    private static final java.util.Set<WaybillPaymentAttemptStatus> REPLACEABLE_PAYMENT_ATTEMPT_STATUSES = EnumSet.of(
            WaybillPaymentAttemptStatus.INITIALIZING,
            WaybillPaymentAttemptStatus.INITIALIZATION_UNKNOWN,
            WaybillPaymentAttemptStatus.INITIALIZED,
            WaybillPaymentAttemptStatus.FAILED,
            WaybillPaymentAttemptStatus.ABANDONED
    );

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@"
                    + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$"
    );

    private final WaybillRepository waybillRepository;
    private final UserRepository userRepository;
    private final WaybillPartyRepository partyRepository;
    private final WaybillParcelRepository parcelRepository;
    private final WaybillInvitationRepository invitationRepository;
    private final WaybillPaymentAttemptRepository paymentAttemptRepository;
    private final WaybillShipmentRepository shipmentRepository;
    private final WaybillShippingQuoteRepository quoteRepository;
    private final WaybillStatusHistoryRepository historyRepository;
    private final WaybillStatusService statusService;
    private final OrderService orderService;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    @Value("${waybill.invitation-ttl:PT168H}")
    private Duration invitationTtl;

    @Transactional
    public CreateWaybillResponse create(CreateWaybillRequest request, String idempotencyKey) {
        String normalizedKey = requiredText(idempotencyKey, "Idempotency key", 255);
        NormalizedCreate normalized = normalize(request);
        String requestHash = requestHash(normalized);
        User authenticated = orderService.currentUser();
        if (authenticated == null || authenticated.getId() == null) {
            throw new ResourceNotFoundException("Creator account not found");
        }
        User creator = userRepository.findByIdForUpdate(authenticated.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Creator account not found"));

        Waybill existing = waybillRepository.findByCreatorAndCreationIdempotencyKeyForUpdate(
                creator.getId(),
                normalizedKey
        ).orElse(null);
        if (existing != null) {
            if (!constantEquals(existing.getCreationRequestHash(), requestHash)) {
                throw new BusinessException("Idempotency key was already used with a different waybill request");
            }
            return new CreateWaybillResponse(details(existing), null, null, true);
        }
        requireCompleteVerifiedAccount(creator);

        LocalDateTime now = utcNow();
        WaybillStatus initialStatus = WaybillStatus.AWAITING_QUOTE;
        Waybill waybill = waybillRepository.save(Waybill.builder()
                .publicId(UUID.randomUUID().toString())
                .creator(creator)
                .creationIdempotencyKey(normalizedKey)
                .creationRequestHash(requestHash)
                .creatorRole(normalized.creatorRole())
                .payerType(normalized.payerType())
                .description(normalized.description())
                .declaredValue(normalized.declaredValue())
                .currency("NGN")
                .status(initialStatus)
                .paymentStatus(WaybillPaymentStatus.NOT_STARTED)
                .createdAt(now)
                .updatedAt(now)
                .build());

        partyRepository.save(creatorParty(waybill, creator, normalized.creatorAddress(), now));
        partyRepository.save(oppositeParty(waybill, normalized.oppositeParty(), now));
        parcelRepository.save(parcel(waybill, normalized.parcel(), now));
        statusService.recordInitial(waybill, "Waybill created with status " + initialStatus);

        return new CreateWaybillResponse(
                details(waybill),
                null,
                null,
                false
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<WaybillDetailsResponse> list(int page, int size) {
        User creator = orderService.currentUser();
        return PageResponse.from(waybillRepository.findByCreator_Id(
                creator.getId(),
                Pagination.newestFirst(page, size)
        ).map(this::details));
    }

    @Transactional(readOnly = true)
    public WaybillDetailsResponse get(String publicId) {
        User creator = orderService.currentUser();
        Waybill waybill = findByPublicId(publicId);
        requireOwner(waybill, creator);
        return details(waybill);
    }

    @Transactional(readOnly = true)
    public List<WaybillStatusHistoryResponse> history(String publicId) {
        User creator = orderService.currentUser();
        Waybill waybill = findByPublicId(publicId);
        requireOwner(waybill, creator);
        return historyRepository.findByWaybill_IdOrderByCreatedAtAscIdAsc(waybill.getId()).stream()
                .map(item -> new WaybillStatusHistoryResponse(
                        item.getFromStatus(),
                        item.getToStatus(),
                        item.getReason(),
                        item.getCreatedAt()
                ))
                .toList();
    }

    @Transactional
    public WaybillInvitationReissueResponse reissueInvitation(String publicId) {
        String normalizedPublicId = requiredText(publicId, "Waybill public ID", 36);
        User creator = orderService.currentUser();
        Waybill waybill = waybillRepository.findByPublicIdForUpdate(normalizedPublicId)
                .orElseThrow(() -> new ResourceNotFoundException("Waybill not found"));
        requireOwner(waybill, creator);

        replacementInvitationKind(waybill);
        LocalDateTime now = utcNow();
        supersedeOpenPaymentAttempts(waybill, now);
        revokeActiveInvitations(waybill, now);

        IssuedInvitation issued = issueInvitation(
                waybill,
                WaybillInvitationKind.PAYMENT_ONLY,
                true,
                now
        );
        return new WaybillInvitationReissueResponse(issued.link(), issued.expiresAt());
    }

    private WaybillDetailsResponse details(Waybill waybill) {
        WaybillParcel parcel = parcelRepository.findByWaybill_Id(waybill.getId()).orElse(null);
        WaybillShipment shipment = shipmentRepository.findByWaybill_Id(waybill.getId()).orElse(null);
        WaybillShippingQuote selectedQuote = selectedQuote(waybill.getId());
        LocalDateTime now = utcNow();
        return new WaybillDetailsResponse(
                waybill.getPublicId(),
                waybill.getCreatorRole(),
                payerRole(waybill),
                waybill.getDescription(),
                waybill.getDeclaredValue(),
                waybill.getCurrency(),
                waybill.getStatus(),
                waybill.getPaymentStatus(),
                pricing(waybill),
                partyRepository.findByWaybill_IdOrderByRoleAsc(waybill.getId()).stream()
                        .map(this::partyResponse)
                        .toList(),
                parcelResponse(parcel),
                quoteResponse(selectedQuote),
                shipmentResponse(shipment),
                invitationRepository.findActiveByWaybillId(waybill.getId(), now).stream()
                        .map(invitation -> invitationResponse(invitation, now))
                        .toList(),
                waybill.getPaymentDueAt(),
                waybill.getFundedAt(),
                waybill.getCompletedAt(),
                waybill.getCancellationReason(),
                waybill.getCancellationRequestedAt(),
                waybill.getCreatedAt(),
                waybill.getUpdatedAt()
        );
    }

    private WaybillParty creatorParty(
            Waybill waybill,
            User creator,
            NormalizedAddress address,
            LocalDateTime now
    ) {
        return WaybillParty.builder()
                .waybill(waybill)
                .user(creator)
                .role(waybill.getCreatorRole())
                .source(WaybillPartySource.ACCOUNT_SNAPSHOT)
                .firstName(creator.getFirstname().trim())
                .lastName(creator.getLastname().trim())
                .email(normalizeEmail(creator.getEmail()))
                .phoneNumber(NigerianPhoneNumber.normalize(creator.getPhoneNumber()))
                .addressLine1(address.line1())
                .addressLine2(address.line2())
                .city(address.city())
                .state(address.state())
                .country(address.country())
                .postalCode(address.postalCode())
                .detailsCompletedAt(now)
                .contactVerifiedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private WaybillParty oppositeParty(
            Waybill waybill,
            NormalizedParty party,
            LocalDateTime now
    ) {
        return WaybillParty.builder()
                .waybill(waybill)
                .role(waybill.getCreatorRole().opposite())
                .source(WaybillPartySource.CREATOR_PROVIDED)
                .firstName(party.firstName())
                .lastName(party.lastName())
                .email(party.email())
                .phoneNumber(party.phoneNumber())
                .addressLine1(party.address().line1())
                .addressLine2(party.address().line2())
                .city(party.address().city())
                .state(party.address().state())
                .country(party.address().country())
                .postalCode(party.address().postalCode())
                .detailsCompletedAt(now)
                .contactVerifiedAt(null)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private WaybillParcel parcel(Waybill waybill, NormalizedParcel parcel, LocalDateTime now) {
        return WaybillParcel.builder()
                .waybill(waybill)
                .weightKg(parcel.weightKg())
                .lengthCm(parcel.lengthCm())
                .widthCm(parcel.widthCm())
                .heightCm(parcel.heightCm())
                .quantity(1)
                .parcelType("parcel")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private IssuedInvitation issueInvitation(
            Waybill waybill,
            WaybillInvitationKind kind,
            boolean canPay,
            LocalDateTime now
    ) {
        if (invitationTtl == null || invitationTtl.isZero() || invitationTtl.isNegative()) {
            throw new IllegalStateException("waybill.invitation-ttl must be positive");
        }

        String rawToken = randomToken();
        LocalDateTime expiresAt = now.plus(invitationTtl);
        if (kind == WaybillInvitationKind.PAYMENT_ONLY) {
            if (waybill.getPaymentDueAt() == null || !waybill.getPaymentDueAt().isAfter(now)) {
                throw new BusinessException("Waybill payment window has expired");
            }
            if (waybill.getPaymentDueAt().isBefore(expiresAt)) {
                expiresAt = waybill.getPaymentDueAt();
            }
        }
        WaybillParty payer = partyRepository.findByWaybill_IdAndRole(
                        waybill.getId(),
                        payerRole(waybill)
                )
                .orElseThrow(() -> new BusinessException("Waybill payer details are not available"));
        WaybillInvitation invitation = WaybillInvitation.builder()
                .waybill(waybill)
                .kind(kind)
                .targetPartyRole(kind == WaybillInvitationKind.PARTY_COMPLETION
                        ? waybill.getCreatorRole().opposite()
                        : null)
                .canPay(canPay)
                .tokenHash(hash(rawToken.getBytes(StandardCharsets.UTF_8)))
                .stagedFirstName(payer.getFirstName())
                .stagedLastName(payer.getLastName())
                .stagedEmail(payer.getEmail())
                .stagedPhoneNumber(payer.getPhoneNumber())
                .detailsCompletedAt(payer.getDetailsCompletedAt())
                .expiresAt(expiresAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
        invitationRepository.save(invitation);
        String baseUrl = frontendBaseUrl == null ? "" : frontendBaseUrl.replaceAll("/+$", "");
        return new IssuedInvitation(
                baseUrl + "/waybills/invitations/" + rawToken,
                expiresAt
        );
    }

    private WaybillInvitationKind replacementInvitationKind(Waybill waybill) {
        WaybillStatus status = waybill.getStatus();
        WaybillPaymentStatus paymentStatus = waybill.getPaymentStatus();
        boolean funded = waybill.getFundedAt() != null
                || waybill.getFundingPaymentReference() != null
                || paymentStatus == WaybillPaymentStatus.CONFIRMED;
        boolean closed = status == WaybillStatus.COMPLETED
                || status == WaybillStatus.CANCELLED
                || status == WaybillStatus.REFUNDED
                || status == WaybillStatus.CANCELLATION_PENDING
                || status == WaybillStatus.REFUND_PENDING
                || status == WaybillStatus.PAYMENT_REVIEW
                || status == WaybillStatus.MANUAL_REVIEW
                || status == WaybillStatus.SHIPMENT_BOOKING
                || status == WaybillStatus.READY_FOR_DELIVERY
                || status == WaybillStatus.SHIPPING_FAILED
                || paymentStatus == WaybillPaymentStatus.REFUND_PENDING
                || paymentStatus == WaybillPaymentStatus.REFUNDED
                || paymentStatus == WaybillPaymentStatus.REVIEW;
        if (funded || closed) {
            throw new BusinessException("Waybill invitation cannot be reissued in its current state");
        }
        if (status == WaybillStatus.AWAITING_PAYMENT
                && waybill.getPayerType() == WaybillPayerType.INVITED_PAYER
                && paymentStatus == WaybillPaymentStatus.PENDING) {
            return WaybillInvitationKind.PAYMENT_ONLY;
        }
        throw new BusinessException("Waybill does not currently require a payment link");
    }

    private NormalizedCreate normalize(CreateWaybillRequest request) {
        if (request == null) {
            throw new BusinessException("Waybill request is required");
        }
        if (request.creatorRole() == null) {
            throw new BusinessException("Creator role is required");
        }
        if (request.payerRole() == null) {
            throw new BusinessException("Payer role is required");
        }
        if (request.oppositeParty() == null) {
            throw new BusinessException("Opposite party details are required");
        }
        WaybillPayerType payerType = request.payerRole() == request.creatorRole()
                ? WaybillPayerType.CREATOR
                : WaybillPayerType.INVITED_PAYER;
        return new NormalizedCreate(
                request.creatorRole(),
                request.payerRole(),
                payerType,
                requiredText(request.description(), "Description", 2000),
                requiredDecimal(request.declaredValue(), 2, "Declared value"),
                normalizeAddress(request.creatorAddress(), "Creator address"),
                normalizeParcel(request.parcel()),
                normalizeParty(request.oppositeParty())
        );
    }

    private void revokeActiveInvitations(Waybill waybill, LocalDateTime now) {
        invitationRepository.findActiveByWaybillIdForUpdate(waybill.getId(), now)
                .forEach(invitation -> {
                    invitation.setCanPay(false);
                    invitation.setAccessTokenHash(null);
                    invitation.setRevokedAt(now);
                    invitation.setUpdatedAt(now);
                    invitationRepository.save(invitation);
                });
    }

    private void supersedeOpenPaymentAttempts(Waybill waybill, LocalDateTime now) {
        List<WaybillPaymentAttempt> attempts = paymentAttemptRepository
                .findByWaybill_IdAndStatusInOrderByCreatedAtDesc(
                        waybill.getId(),
                        REPLACEABLE_PAYMENT_ATTEMPT_STATUSES
                );
        attempts.forEach(attempt -> {
            attempt.setStatus(WaybillPaymentAttemptStatus.SUPERSEDED);
            attempt.setFailureReason("Payment link was replaced");
            attempt.setNextRecheckAt(now);
            attempt.setUpdatedAt(now);
            paymentAttemptRepository.save(attempt);
        });
    }

    public WaybillPartyRole payerRole(Waybill waybill) {
        return waybill.getPayerType() == WaybillPayerType.CREATOR
                ? waybill.getCreatorRole()
                : waybill.getCreatorRole().opposite();
    }

    private NormalizedParty normalizeParty(WaybillPartyRequest request) {
        return new NormalizedParty(
                requiredText(request.firstName(), "First name", 100),
                requiredText(request.lastName(), "Last name", 100),
                normalizeEmail(request.email()),
                NigerianPhoneNumber.normalize(request.phoneNumber()),
                normalizeAddress(request.address(), "Party address")
        );
    }

    private NormalizedAddress normalizeAddress(OrderAddressRequest request, String field) {
        if (request == null) {
            throw new BusinessException(field + " is required");
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

    private NormalizedParcel normalizeParcel(OrderParcelRequest request) {
        if (request == null) {
            throw new BusinessException("Parcel is required");
        }
        return new NormalizedParcel(
                requiredDecimal(request.weightKg(), 3, "Parcel weight"),
                optionalDecimal(request.lengthCm(), 2, "Parcel length"),
                optionalDecimal(request.widthCm(), 2, "Parcel width"),
                optionalDecimal(request.heightCm(), 2, "Parcel height")
        );
    }

    private BigDecimal requiredDecimal(BigDecimal value, int scale, String field) {
        if (value == null) {
            throw new BusinessException(field + " is required");
        }
        if (value.signum() <= 0) {
            throw new BusinessException(field + " must be greater than zero");
        }
        try {
            return value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new BusinessException(field + " has too many decimal places");
        }
    }

    private BigDecimal optionalDecimal(BigDecimal value, int scale, String field) {
        return value == null ? null : requiredDecimal(value, scale, field);
    }

    private String requestHash(NormalizedCreate request) {
        try {
            return hash(objectMapper.writeValueAsBytes(request));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to secure waybill creation request", exception);
        }
    }

    private String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Waybill findByPublicId(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            throw new ResourceNotFoundException("Waybill not found");
        }
        return waybillRepository.findByPublicId(publicId.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Waybill not found"));
    }

    private void requireOwner(Waybill waybill, User creator) {
        if (!waybill.getCreator().getId().equals(creator.getId())) {
            throw new AccessDeniedException("Waybill does not belong to current user");
        }
    }

    private void requireCompleteVerifiedAccount(User user) {
        if (user.getEmailVerifiedAt() == null || !user.isEnabled()) {
            throw new BusinessException("A verified account is required before creating a waybill");
        }
        requiredText(user.getFirstname(), "Account first name", 100);
        requiredText(user.getLastname(), "Account last name", 100);
        if (user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) {
            throw new BusinessException("Account phone number is required before creating a waybill");
        }
        normalizeEmail(user.getEmail());
        NigerianPhoneNumber.normalize(user.getPhoneNumber());
    }

    private String normalizeEmail(String email) {
        String normalized = requiredText(email, "Email", 255).toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException("Email must be valid");
        }
        return normalized;
    }

    private String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException("Optional address line must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private boolean constantEquals(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
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

    private WaybillPartyResponse partyResponse(WaybillParty party) {
        return new WaybillPartyResponse(
                party.getRole(),
                party.getSource(),
                party.getFirstName(),
                party.getLastName(),
                party.getEmail(),
                party.getPhoneNumber(),
                new AddressResponse(
                        party.getAddressLine1(),
                        party.getAddressLine2(),
                        party.getCity(),
                        party.getState(),
                        party.getCountry(),
                        party.getPostalCode()
                ),
                party.getUser() != null,
                party.getDetailsCompletedAt(),
                party.getContactVerifiedAt()
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

    private WaybillInvitationSummaryResponse invitationResponse(
            WaybillInvitation invitation,
            LocalDateTime now
    ) {
        return new WaybillInvitationSummaryResponse(
                invitation.getId(),
                invitation.getKind(),
                invitation.getTargetPartyRole(),
                invitation.isCanPay(),
                invitation.getStagedFirstName(),
                invitation.getStagedLastName(),
                invitation.getStagedEmail(),
                invitation.getStagedPhoneNumber(),
                invitation.getRevokedAt() == null && invitation.getExpiresAt().isAfter(now),
                invitation.getExpiresAt(),
                invitation.getVerifiedAt(),
                invitation.getDetailsCompletedAt(),
                invitation.getPaymentCompletedAt(),
                invitation.getRevokedAt(),
                invitation.getCreatedAt()
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

    private record NormalizedCreate(
            WaybillPartyRole creatorRole,
            WaybillPartyRole payerRole,
            WaybillPayerType payerType,
            String description,
            BigDecimal declaredValue,
            NormalizedAddress creatorAddress,
            NormalizedParcel parcel,
            NormalizedParty oppositeParty
    ) {
    }

    private record NormalizedParty(
            String firstName,
            String lastName,
            String email,
            String phoneNumber,
            NormalizedAddress address
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

    private record NormalizedParcel(
            BigDecimal weightKg,
            BigDecimal lengthCm,
            BigDecimal widthCm,
            BigDecimal heightCm
    ) {
    }

    private record IssuedInvitation(String link, LocalDateTime expiresAt) {
    }

}
