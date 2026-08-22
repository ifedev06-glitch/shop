package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.WaybillCheckoutResponse;
import com.example.shopsecure.entity.ShippingQuoteStatus;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillInvitation;
import com.example.shopsecure.entity.WaybillPayerType;
import com.example.shopsecure.entity.WaybillPaymentAttempt;
import com.example.shopsecure.entity.WaybillPaymentAttemptStatus;
import com.example.shopsecure.entity.WaybillPaymentStatus;
import com.example.shopsecure.entity.WaybillShippingQuote;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.provider.payment.PaymentInitResponse;
import com.example.shopsecure.provider.payment.PaymentProvider;
import com.example.shopsecure.provider.payment.PaymentRequest;
import com.example.shopsecure.repository.WaybillInvitationRepository;
import com.example.shopsecure.repository.WaybillPaymentAttemptRepository;
import com.example.shopsecure.repository.WaybillRepository;
import com.example.shopsecure.repository.WaybillShippingQuoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaybillCheckoutService {

    private static final Set<WaybillPaymentAttemptStatus> OPEN_ATTEMPT_STATUSES = EnumSet.of(
            WaybillPaymentAttemptStatus.INITIALIZING,
            WaybillPaymentAttemptStatus.INITIALIZATION_UNKNOWN,
            WaybillPaymentAttemptStatus.INITIALIZED
    );

    private final WaybillRepository waybillRepository;
    private final WaybillInvitationRepository invitationRepository;
    private final WaybillShippingQuoteRepository quoteRepository;
    private final WaybillPaymentAttemptRepository attemptRepository;
    private final WaybillAccessService accessService;
    private final OrderService orderService;
    private final PaymentProvider paymentProvider;
    private final PlatformTransactionManager transactionManager;

    @Value("${app.base-url}")
    private String appBaseUrl;

    @Value("${waybill.payment-reconciliation.retry-delay:PT5M}")
    private Duration recheckDelay;

    public WaybillCheckoutResponse initializeCreator(String publicId, String idempotencyKey) {
        String normalizedPublicId = requiredText(publicId, "Waybill public ID", 36);
        String normalizedKey = requiredText(idempotencyKey, "Idempotency-Key", 255);
        User creator = orderService.currentUser();
        PreparedCheckout prepared = requireResult(transaction().execute(status -> prepareCreator(
                normalizedPublicId,
                creator,
                normalizedKey
        )));
        return initialize(prepared);
    }

    public WaybillCheckoutResponse initializeInvited(
            String rawInvitation,
            String accessToken,
            String idempotencyKey
    ) {
        String normalizedKey = requiredText(idempotencyKey, "Idempotency-Key", 255);
        InvitedAccess invitedAccess = requireResult(transaction().execute(status -> {
            WaybillInvitation invitation = accessService.requirePayAccess(rawInvitation, accessToken);
            return new InvitedAccess(
                    invitation.getId(),
                    invitation.getWaybill().getId(),
                    invitation.getStagedEmail()
            );
        }));
        PreparedCheckout prepared = requireResult(transaction().execute(status -> prepareInvited(
                invitedAccess,
                normalizedKey
        )));
        return initialize(prepared);
    }

    private PreparedCheckout prepareCreator(String publicId, User creator, String idempotencyKey) {
        Waybill waybill = waybillRepository.findByPublicIdForUpdate(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Waybill not found"));
        if (!waybill.getCreator().getId().equals(creator.getId())) {
            throw new AccessDeniedException("Waybill does not belong to current user");
        }
        if (waybill.getPayerType() != WaybillPayerType.CREATOR) {
            throw new AccessDeniedException("This waybill is payable only by its invited payer");
        }
        return prepare(
                waybill,
                null,
                WaybillPayerType.CREATOR,
                creator.getEmail(),
                idempotencyKey
        );
    }

    private PreparedCheckout prepareInvited(InvitedAccess access, String idempotencyKey) {
        Waybill waybill = waybillRepository.findByIdForUpdate(access.waybillId())
                .orElseThrow(() -> new ResourceNotFoundException("Waybill not found"));
        WaybillInvitation invitation = invitationRepository.findById(access.invitationId())
                .orElseThrow(() -> new AccessDeniedException("Waybill access is invalid"));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (!invitation.getWaybill().getId().equals(waybill.getId())
                || waybill.getPayerType() != WaybillPayerType.INVITED_PAYER
                || !invitation.isCanPay()
                || invitation.getVerifiedAt() == null
                || invitation.getPaymentCompletedAt() != null
                || invitation.getRevokedAt() != null
                || !invitation.getExpiresAt().isAfter(now)) {
            throw new AccessDeniedException("Waybill access does not permit payment");
        }
        return prepare(
                waybill,
                invitation,
                WaybillPayerType.INVITED_PAYER,
                access.customerEmail(),
                idempotencyKey
        );
    }

    private PreparedCheckout prepare(
            Waybill waybill,
            WaybillInvitation invitation,
            WaybillPayerType payerType,
            String customerEmail,
            String idempotencyKey
    ) {
        WaybillPaymentAttempt replay = attemptRepository
                .findByWaybillIdAndIdempotencyKeyForUpdate(waybill.getId(), idempotencyKey)
                .orElse(null);
        if (replay != null) {
            requireAttemptAccess(replay, payerType, invitation);
            if (replay.getStatus() == WaybillPaymentAttemptStatus.SUPERSEDED) {
                throw new BusinessException(
                        "This waybill payment attempt is no longer usable; retry with a new Idempotency-Key"
                );
            }
            return PreparedCheckout.replay(response(waybill, replay));
        }

        WaybillPaymentAttempt open = attemptRepository
                .findFirstByWaybill_IdAndStatusInOrderByCreatedAtDesc(
                        waybill.getId(),
                        OPEN_ATTEMPT_STATUSES
                )
                .orElse(null);
        if (open != null) {
            requireAttemptAccess(open, payerType, invitation);
            throw new BusinessException(
                    "Another waybill payment attempt is open; retry with its Idempotency-Key"
            );
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        requirePayable(waybill, now);

        String email = requiredText(customerEmail, "Payer email", 255);
        WaybillPaymentAttempt attempt = attemptRepository.saveAndFlush(WaybillPaymentAttempt.builder()
                .waybill(waybill)
                .invitation(invitation)
                .payerType(payerType)
                .provider("PAYSTACK")
                .reference(newReference(waybill.getPublicId()))
                .idempotencyKey(idempotencyKey)
                .customerEmail(email)
                .amount(waybill.getTotalAmount())
                .currency(waybill.getCurrency())
                .status(WaybillPaymentAttemptStatus.INITIALIZING)
                .nextRecheckAt(now.plus(validRecheckDelay()))
                .createdAt(now)
                .updatedAt(now)
                .build());
        return new PreparedCheckout(
                waybill.getId(),
                attempt.getId(),
                waybill.getPublicId(),
                attempt.getReference(),
                attempt.getCustomerEmail(),
                attempt.getAmount(),
                attempt.getCurrency(),
                true,
                null
        );
    }

    private WaybillCheckoutResponse initialize(PreparedCheckout prepared) {
        if (!prepared.dispatch()) {
            return prepared.replayResponse();
        }

        PaymentInitResponse initialized;
        try {
            initialized = withoutTransaction().execute(status ->
                    paymentProvider.initializeTransaction(new PaymentRequest(
                            prepared.amount(),
                            prepared.customerEmail(),
                            prepared.reference(),
                            prepared.currency(),
                            callbackUrl(prepared.publicId()),
                            Map.of(
                                    "resource_type", "WAYBILL",
                                    "waybill_public_id", prepared.publicId(),
                                    "payment_attempt_id", prepared.attemptId()
                            )
                    ))
            );
        } catch (RuntimeException exception) {
            log.warn("Waybill checkout initialization outcome is unknown: attemptId={}", prepared.attemptId());
            return markInitializationUnknown(prepared);
        }

        if (initialized == null
                || !prepared.reference().equals(initialized.reference())
                || !hasText(initialized.checkoutUrl())
                || !hasText(initialized.accessCode())) {
            return markInitializationUnknown(prepared);
        }
        return requireResult(transaction().execute(status -> applyInitialization(prepared, initialized)));
    }

    private WaybillCheckoutResponse applyInitialization(
            PreparedCheckout prepared,
            PaymentInitResponse initialized
    ) {
        Waybill waybill = requireWaybill(prepared.waybillId());
        WaybillPaymentAttempt attempt = requireAttempt(prepared.attemptId());
        if (!prepared.reference().equals(attempt.getReference())) {
            throw new IllegalStateException("Waybill payment attempt reference changed");
        }
        boolean changed = false;
        if (!initialized.checkoutUrl().equals(attempt.getAuthorizationUrl())) {
            attempt.setAuthorizationUrl(initialized.checkoutUrl());
            changed = true;
        }
        if (!initialized.accessCode().equals(attempt.getAccessCode())) {
            attempt.setAccessCode(initialized.accessCode());
            changed = true;
        }
        if (attempt.getStatus() == WaybillPaymentAttemptStatus.INITIALIZING
                || attempt.getStatus() == WaybillPaymentAttemptStatus.INITIALIZATION_UNKNOWN) {
            attempt.setStatus(WaybillPaymentAttemptStatus.INITIALIZED);
            attempt.setFailureReason(null);
            attempt.setNextRecheckAt(LocalDateTime.now(ZoneOffset.UTC).plus(validRecheckDelay()));
            changed = true;
        }
        if (changed) {
            attempt.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            attemptRepository.save(attempt);
        }
        return response(waybill, attempt);
    }

    private WaybillCheckoutResponse markInitializationUnknown(PreparedCheckout prepared) {
        return requireResult(transaction().execute(status -> {
            Waybill waybill = requireWaybill(prepared.waybillId());
            WaybillPaymentAttempt attempt = requireAttempt(prepared.attemptId());
            if (!prepared.reference().equals(attempt.getReference())) {
                throw new IllegalStateException("Waybill payment attempt reference changed");
            }
            if (attempt.getStatus() == WaybillPaymentAttemptStatus.INITIALIZING
                    || attempt.getStatus() == WaybillPaymentAttemptStatus.INITIALIZATION_UNKNOWN) {
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                attempt.setStatus(WaybillPaymentAttemptStatus.INITIALIZATION_UNKNOWN);
                attempt.setFailureReason("Payment initialization outcome requires provider verification");
                attempt.setNextRecheckAt(now.plus(validRecheckDelay()));
                attempt.setUpdatedAt(now);
                attemptRepository.save(attempt);
            }
            return response(waybill, attempt);
        }));
    }

    private void requirePayable(Waybill waybill, LocalDateTime now) {
        if (waybill.getStatus() != WaybillStatus.AWAITING_PAYMENT
                || waybill.getPaymentStatus() != WaybillPaymentStatus.PENDING) {
            throw new BusinessException("Waybill is not awaiting checkout payment");
        }
        if (waybill.getTotalAmount() == null || waybill.getTotalAmount().signum() <= 0) {
            throw new BusinessException("Waybill total is not available");
        }
        if (waybill.getPaymentDueAt() == null || !waybill.getPaymentDueAt().isAfter(now)) {
            throw new BusinessException("Waybill payment window has expired");
        }
        WaybillShippingQuote quote = quoteRepository.findByWaybillIdAndStatusForUpdate(
                        waybill.getId(),
                        ShippingQuoteStatus.SELECTED
                )
                .orElseThrow(() -> new BusinessException("Selected waybill shipping quote was not found"));
        if (!quote.getExpiresAt().isAfter(now)) {
            throw new BusinessException("Selected waybill shipping quote has expired");
        }
        if (quote.getTotalAmount() == null
                || quote.getTotalAmount().compareTo(waybill.getTotalAmount()) != 0
                || !exactCurrency(quote.getCurrency(), waybill.getCurrency())) {
            throw new BusinessException("Waybill frozen total does not match its selected quote");
        }
    }

    private Waybill requireWaybill(Long waybillId) {
        return waybillRepository.findByIdForUpdate(waybillId)
                .orElseThrow(() -> new ResourceNotFoundException("Waybill not found"));
    }

    private WaybillPaymentAttempt requireAttempt(Long attemptId) {
        return attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Waybill payment attempt not found"));
    }

    private void requireAttemptAccess(
            WaybillPaymentAttempt attempt,
            WaybillPayerType payerType,
            WaybillInvitation invitation
    ) {
        if (attempt.getPayerType() != payerType) {
            throw new AccessDeniedException("Waybill payment attempt belongs to a different payer");
        }
        if (payerType == WaybillPayerType.INVITED_PAYER
                && (invitation == null
                || attempt.getInvitation() == null
                || !invitation.getId().equals(attempt.getInvitation().getId()))) {
            throw new AccessDeniedException("Waybill payment attempt belongs to a different invitation");
        }
    }

    private String newReference(String publicId) {
        String prefix = "waybill-" + publicId.replace("-", "") + "-";
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = prefix
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            if (!attemptRepository.existsByReference(reference)) {
                return reference;
            }
        }
        throw new BusinessException("Unable to create a unique waybill payment reference");
    }

    private String callbackUrl(String publicId) {
        String baseUrl = appBaseUrl == null ? "" : appBaseUrl.replaceAll("/+$", "");
        return baseUrl + "/waybills/" + publicId;
    }

    private WaybillCheckoutResponse response(Waybill waybill, WaybillPaymentAttempt attempt) {
        return new WaybillCheckoutResponse(
                waybill.getPublicId(),
                attempt.getId(),
                attempt.getPayerType() == WaybillPayerType.CREATOR
                        ? waybill.getCreatorRole()
                        : waybill.getCreatorRole().opposite(),
                waybill.getStatus(),
                waybill.getPaymentStatus(),
                attempt.getStatus(),
                attempt.getAmount(),
                attempt.getCurrency(),
                attempt.getReference(),
                attempt.getAuthorizationUrl(),
                attempt.getAccessCode(),
                attempt.getCreatedAt()
        );
    }

    private Duration validRecheckDelay() {
        return recheckDelay == null || recheckDelay.isNegative() || recheckDelay.isZero()
                ? Duration.ofMinutes(5)
                : recheckDelay;
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private TransactionTemplate withoutTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return transaction;
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

    private boolean exactCurrency(String first, String second) {
        return first != null && first.equals(second);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private <T> T requireResult(T result) {
        if (result == null) {
            throw new IllegalStateException("Waybill checkout transaction returned no result");
        }
        return result;
    }

    private record InvitedAccess(Long invitationId, Long waybillId, String customerEmail) {
    }

    private record PreparedCheckout(
            Long waybillId,
            Long attemptId,
            String publicId,
            String reference,
            String customerEmail,
            java.math.BigDecimal amount,
            String currency,
            boolean dispatch,
            WaybillCheckoutResponse replayResponse
    ) {
        private static PreparedCheckout replay(WaybillCheckoutResponse response) {
            return new PreparedCheckout(null, null, null, null, null, null, null, false, response);
        }
    }
}
