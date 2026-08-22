package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.CreateWaybillRequest;
import com.example.shopsecure.dto.request.OrderAddressRequest;
import com.example.shopsecure.dto.request.OrderParcelRequest;
import com.example.shopsecure.dto.request.WaybillPartyRequest;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillInvitation;
import com.example.shopsecure.entity.WaybillInvitationKind;
import com.example.shopsecure.entity.WaybillParty;
import com.example.shopsecure.entity.WaybillPartyRole;
import com.example.shopsecure.entity.WaybillPayerType;
import com.example.shopsecure.entity.WaybillPaymentAttempt;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.repository.UserRepository;
import com.example.shopsecure.repository.WaybillInvitationRepository;
import com.example.shopsecure.repository.WaybillParcelRepository;
import com.example.shopsecure.repository.WaybillPartyRepository;
import com.example.shopsecure.repository.WaybillPaymentAttemptRepository;
import com.example.shopsecure.repository.WaybillRepository;
import com.example.shopsecure.repository.WaybillShipmentRepository;
import com.example.shopsecure.repository.WaybillShippingQuoteRepository;
import com.example.shopsecure.repository.WaybillStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaybillServiceTest {

    private WaybillRepository waybillRepository;
    private UserRepository userRepository;
    private WaybillPartyRepository partyRepository;
    private WaybillParcelRepository parcelRepository;
    private WaybillInvitationRepository invitationRepository;
    private WaybillPaymentAttemptRepository paymentAttemptRepository;
    private WaybillShipmentRepository shipmentRepository;
    private WaybillShippingQuoteRepository quoteRepository;
    private WaybillStatusHistoryRepository historyRepository;
    private WaybillStatusService statusService;
    private OrderService orderService;
    private ObjectMapper objectMapper;
    private WaybillService service;
    private User creator;

    @BeforeEach
    void setUp() throws Exception {
        waybillRepository = mock(WaybillRepository.class);
        userRepository = mock(UserRepository.class);
        partyRepository = mock(WaybillPartyRepository.class);
        parcelRepository = mock(WaybillParcelRepository.class);
        invitationRepository = mock(WaybillInvitationRepository.class);
        paymentAttemptRepository = mock(WaybillPaymentAttemptRepository.class);
        shipmentRepository = mock(WaybillShipmentRepository.class);
        quoteRepository = mock(WaybillShippingQuoteRepository.class);
        historyRepository = mock(WaybillStatusHistoryRepository.class);
        statusService = mock(WaybillStatusService.class);
        orderService = mock(OrderService.class);
        objectMapper = mock(ObjectMapper.class);
        service = new WaybillService(
                waybillRepository,
                userRepository,
                partyRepository,
                parcelRepository,
                invitationRepository,
                paymentAttemptRepository,
                shipmentRepository,
                quoteRepository,
                historyRepository,
                statusService,
                orderService,
                objectMapper
        );
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "https://shop.test/");
        ReflectionTestUtils.setField(service, "invitationTtl", Duration.ofDays(7));

        creator = User.builder()
                .id(1L)
                .firstname("Creator")
                .lastname("Owner")
                .email("creator@example.com")
                .phoneNumber("+2348012345678")
                .emailVerifiedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1))
                .build();
        when(orderService.currentUser()).thenReturn(creator);
        when(userRepository.findByIdForUpdate(creator.getId())).thenReturn(Optional.of(creator));
        when(objectMapper.writeValueAsBytes(any())).thenAnswer(invocation ->
                invocation.getArgument(0).toString().getBytes(StandardCharsets.UTF_8));
        when(waybillRepository.save(any(Waybill.class))).thenAnswer(invocation -> {
            Waybill waybill = invocation.getArgument(0);
            waybill.setId(100L);
            return waybill;
        });
        when(invitationRepository.save(any(WaybillInvitation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        stubDetails();
    }

    @Test
    void locksCreatorAndReplaysSameNormalizedRequestButRejectsMismatch() {
        CreateWaybillRequest first = request(
                "  A boxed phone  ",
                oppositeParty(),
                WaybillPayerType.CREATOR
        );
        CreateWaybillRequest equivalent = request(
                "A boxed phone",
                oppositeParty(),
                WaybillPayerType.CREATOR
        );

        when(waybillRepository.findByCreatorAndCreationIdempotencyKeyForUpdate(1L, "create-1"))
                .thenReturn(Optional.empty());
        var created = service.create(first, "  create-1  ");

        ArgumentCaptor<Waybill> waybillCaptor = ArgumentCaptor.forClass(Waybill.class);
        verify(waybillRepository).save(waybillCaptor.capture());
        Waybill persisted = waybillCaptor.getValue();
        when(waybillRepository.findByCreatorAndCreationIdempotencyKeyForUpdate(1L, "create-1"))
                .thenReturn(Optional.of(persisted));

        var replay = service.create(equivalent, "create-1");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.details().publicId()).isEqualTo(created.details().publicId());
        verify(waybillRepository, times(1)).save(any(Waybill.class));
        InOrder lockingOrder = inOrder(userRepository, waybillRepository);
        lockingOrder.verify(userRepository).findByIdForUpdate(1L);
        lockingOrder.verify(waybillRepository)
                .findByCreatorAndCreationIdempotencyKeyForUpdate(1L, "create-1");

        assertThatThrownBy(() -> service.create(
                request("A different phone", oppositeParty(), WaybillPayerType.CREATOR),
                "create-1"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("Idempotency key was already used with a different waybill request");
    }

    @Test
    void createsCompleteInvitedPayerWaybillWithoutExposingALinkBeforeQuoteSelection() {
        when(waybillRepository.findByCreatorAndCreationIdempotencyKeyForUpdate(1L, "payer-key"))
                .thenReturn(Optional.empty());

        var response = service.create(
                request("Camera", oppositeParty(), WaybillPayerType.INVITED_PAYER),
                "payer-key"
        );

        assertThat(response.paymentLink()).isNull();
        assertThat(response.details().status()).isEqualTo(WaybillStatus.AWAITING_QUOTE);
        assertThat(response.details().payerRole()).isEqualTo(WaybillPartyRole.RECIPIENT);
        verify(invitationRepository, never()).save(any());
        verify(partyRepository, times(2)).save(any(WaybillParty.class));
    }

    @Test
    void rejectsPayerPartyCombinationsThatCannotProduceTheRequiredParties() {
        assertThatThrownBy(() -> service.create(
                request("Parcel", null, WaybillPayerType.CREATOR),
                "creator-missing-party"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("Opposite party details are required");

        assertThatThrownBy(() -> service.create(
                request(
                        "Parcel",
                        null,
                        WaybillPayerType.INVITED_PAYER,
                        WaybillPartyRole.RECIPIENT
                ),
                "recipient-creator"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("Opposite party details are required");

        verify(waybillRepository, never()).save(any());
    }

    @Test
    void ownerRotationRevokesAccessAndIssuesExactlyOneStateDerivedReplacement() throws Exception {
        Waybill waybill = waybill(WaybillStatus.AWAITING_PAYMENT);
        waybill.setPaymentStatus(WaybillPaymentStatus.PENDING);
        waybill.setPaymentDueAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(15));
        WaybillInvitation oldInvitation = WaybillInvitation.builder()
                .id(20L)
                .waybill(waybill)
                .kind(WaybillInvitationKind.PAYMENT_ONLY)
                .canPay(true)
                .tokenHash("a".repeat(64))
                .accessTokenHash("b".repeat(64))
                .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(1))
                .createdAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(1))
                .updatedAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(1))
                .build();
        when(waybillRepository.findByPublicIdForUpdate("waybill-1"))
                .thenReturn(Optional.of(waybill));
        when(invitationRepository.findActiveByWaybillIdForUpdate(any(), any()))
                .thenReturn(List.of(oldInvitation));
        WaybillPaymentAttempt oldAttempt = WaybillPaymentAttempt.builder()
                .id(30L)
                .waybill(waybill)
                .payerType(WaybillPayerType.INVITED_PAYER)
                .status(WaybillPaymentAttemptStatus.INITIALIZED)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30))
                .updatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30))
                .build();
        WaybillPaymentAttempt failedAttempt = WaybillPaymentAttempt.builder()
                .id(31L)
                .waybill(waybill)
                .payerType(WaybillPayerType.INVITED_PAYER)
                .status(WaybillPaymentAttemptStatus.FAILED)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(45))
                .updatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(40))
                .build();
        when(paymentAttemptRepository.findByWaybill_IdAndStatusInOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(oldAttempt, failedAttempt));
        when(partyRepository.findByWaybill_IdAndRole(100L, WaybillPartyRole.RECIPIENT))
                .thenReturn(Optional.of(WaybillParty.builder()
                        .waybill(waybill)
                        .role(WaybillPartyRole.RECIPIENT)
                        .firstName("Receiver")
                        .lastName("Person")
                        .email("receiver@example.com")
                        .phoneNumber("+2348098765432")
                        .detailsCompletedAt(LocalDateTime.now(ZoneOffset.UTC))
                        .build()));

        var response = service.reissueInvitation("waybill-1");

        ArgumentCaptor<WaybillInvitation> captor = ArgumentCaptor.forClass(WaybillInvitation.class);
        verify(invitationRepository, times(2)).save(captor.capture());
        List<WaybillInvitation> saved = captor.getAllValues();
        WaybillInvitation replacement = saved.stream()
                .filter(invitation -> invitation.getId() == null)
                .findFirst()
                .orElseThrow();
        assertThat(oldInvitation.getRevokedAt()).isNotNull();
        assertThat(oldInvitation.getAccessTokenHash()).isNull();
        assertThat(oldInvitation.isCanPay()).isFalse();
        assertThat(replacement.getKind()).isEqualTo(WaybillInvitationKind.PAYMENT_ONLY);
        assertThat(replacement.getTargetPartyRole()).isNull();
        assertThat(replacement.isCanPay()).isTrue();
        assertThat(response.expiresAt()).isEqualTo(waybill.getPaymentDueAt());
        assertThat(oldAttempt.getStatus()).isEqualTo(WaybillPaymentAttemptStatus.SUPERSEDED);
        assertThat(oldAttempt.getFailureReason()).isEqualTo("Payment link was replaced");
        assertThat(failedAttempt.getStatus()).isEqualTo(WaybillPaymentAttemptStatus.SUPERSEDED);
        verify(paymentAttemptRepository).save(oldAttempt);
        verify(paymentAttemptRepository).save(failedAttempt);
        String rawToken = response.paymentLink()
                .substring(response.paymentLink().lastIndexOf('/') + 1);
        assertThat(replacement.getTokenHash()).isEqualTo(sha256(rawToken));
        assertThat(saved.stream().filter(invitation -> invitation.getId() == null)).hasSize(1);
    }

    @Test
    void rotationIsOwnerOnlyAndRejectsFundedWaybills() {
        Waybill waybill = waybill(WaybillStatus.AWAITING_QUOTE);
        waybill.setCreator(User.builder().id(99L).build());
        when(waybillRepository.findByPublicIdForUpdate("waybill-1"))
                .thenReturn(Optional.of(waybill));

        assertThatThrownBy(() -> service.reissueInvitation("waybill-1"))
                .isInstanceOf(AccessDeniedException.class);
        verify(invitationRepository, never()).findActiveByWaybillIdForUpdate(any(), any());

        waybill.setCreator(creator);
        waybill.setPaymentStatus(WaybillPaymentStatus.CONFIRMED);
        assertThatThrownBy(() -> service.reissueInvitation("waybill-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Waybill invitation cannot be reissued in its current state");
        verify(invitationRepository, never()).save(any());
    }

    @Test
    void rejectsCreatorSnapshotThatCannotFitWaybillSchemaOrHasInvalidEmail() {
        creator.setFirstname("x".repeat(101));
        assertThatThrownBy(() -> service.create(
                request("Parcel", oppositeParty(), WaybillPayerType.CREATOR),
                "invalid-name"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("Account first name must not exceed 100 characters");
        verify(waybillRepository, never()).save(any());

        creator.setFirstname("Creator");
        creator.setEmail("not-an-email");
        assertThatThrownBy(() -> service.create(
                request("Parcel", oppositeParty(), WaybillPayerType.CREATOR),
                "invalid-email"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("Email must be valid");
    }

    private void stubDetails() {
        when(parcelRepository.findByWaybill_Id(any())).thenReturn(Optional.empty());
        when(shipmentRepository.findByWaybill_Id(any())).thenReturn(Optional.empty());
        when(quoteRepository.findByShipment_Waybill_IdAndStatus(any(), any()))
                .thenReturn(Optional.empty());
        when(partyRepository.findByWaybill_IdOrderByRoleAsc(any())).thenReturn(List.of());
        when(invitationRepository.findActiveByWaybillId(any(), any())).thenReturn(List.of());
    }

    private Waybill waybill(WaybillStatus status) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return Waybill.builder()
                .id(100L)
                .publicId("waybill-1")
                .creator(creator)
                .creationIdempotencyKey("key")
                .creationRequestHash("c".repeat(64))
                .creatorRole(WaybillPartyRole.SENDER)
                .payerType(WaybillPayerType.INVITED_PAYER)
                .description("Parcel")
                .declaredValue(new BigDecimal("1000.00"))
                .currency("NGN")
                .status(status)
                .paymentStatus(WaybillPaymentStatus.NOT_STARTED)
                .createdAt(now.minusHours(1))
                .updatedAt(now.minusHours(1))
                .build();
    }

    private CreateWaybillRequest request(
            String description,
            WaybillPartyRequest oppositeParty,
            WaybillPayerType payerType
    ) {
        return request(description, oppositeParty, payerType, WaybillPartyRole.SENDER);
    }

    private CreateWaybillRequest request(
            String description,
            WaybillPartyRequest oppositeParty,
            WaybillPayerType payerType,
            WaybillPartyRole creatorRole
    ) {
        return new CreateWaybillRequest(
                creatorRole,
                payerType == WaybillPayerType.CREATOR ? creatorRole : creatorRole.opposite(),
                description,
                new BigDecimal("1000.0"),
                new OrderAddressRequest(
                        " 1 Main Street ",
                        " ",
                        " Lagos ",
                        " Lagos ",
                        "ng",
                        " 100001 "
                ),
                new OrderParcelRequest(
                        new BigDecimal("2.0"),
                        new BigDecimal("10.0"),
                        null,
                        null
                ),
                oppositeParty
        );
    }

    private WaybillPartyRequest oppositeParty() {
        return new WaybillPartyRequest(
                "Receiver",
                "Person",
                "receiver@example.com",
                "+2348098765432",
                new OrderAddressRequest(
                        "2 Other Street",
                        null,
                        "Abuja",
                        "FCT",
                        "NG",
                        "900001"
                )
        );
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                value.getBytes(StandardCharsets.UTF_8)
        ));
    }
}
