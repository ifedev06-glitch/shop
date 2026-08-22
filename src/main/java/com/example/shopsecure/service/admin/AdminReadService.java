package com.example.shopsecure.service.admin;

import com.example.shopsecure.dto.response.OrderStatusHistoryResponse;
import com.example.shopsecure.dto.response.PageResponse;
import com.example.shopsecure.dto.response.WaybillStatusHistoryResponse;
import com.example.shopsecure.dto.response.admin.*;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.provider.payment.PaymentTransactionStatus;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.repository.*;
import com.example.shopsecure.repository.admin.*;
import com.example.shopsecure.util.Pagination;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminReadService {

    private static final int MAX_DETAIL_RECORDS = 100;

    private final AdminUserReadRepository userRepository;
    private final AdminWalletReadRepository walletRepository;
    private final AdminOrderReadRepository orderRepository;
    private final AdminWithdrawalReadRepository withdrawalRepository;
    private final AdminSettlementReadRepository settlementRepository;
    private final AdminShipmentReadRepository shipmentRepository;
    private final AdminWalletTransactionReadRepository walletTransactionRepository;
    private final AdminPaymentAttemptReadRepository paymentAttemptRepository;
    private final AdminPaymentReceiptReadRepository paymentReceiptRepository;
    private final AdminOrderHistoryReadRepository historyRepository;
    private final AdminWaybillReadRepository waybillRepository;
    private final AdminWaybillPartyReadRepository waybillPartyRepository;
    private final AdminWaybillQuoteReadRepository waybillQuoteRepository;
    private final AdminWaybillPaymentAttemptReadRepository waybillPaymentAttemptRepository;
    private final AdminWaybillPaymentReceiptReadRepository waybillPaymentReceiptRepository;
    private final AdminWaybillRefundReadRepository waybillRefundRepository;
    private final AdminWaybillInvitationReadRepository waybillInvitationRepository;
    private final AdminWaybillHistoryReadRepository waybillHistoryRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final WithdrawalBankAccountRepository bankAccountRepository;
    private final OrderCounterpartyRepository counterpartyRepository;
    private final OrderParcelRepository parcelRepository;
    private final OrderShipmentRepository orderShipmentRepository;
    private final ShippingQuoteRepository shippingQuoteRepository;
    private final OrderSettlementRepository orderSettlementRepository;
    private final WaybillParcelRepository waybillParcelRepository;
    private final WaybillShipmentRepository waybillShipmentRepository;

    @Transactional(readOnly = true, timeout = 5)
    public PageResponse<AdminUserSummaryResponse> users(
            Role role,
            Boolean verified,
            String email,
            int page,
            int size
    ) {
        Specification<User> specification = all();
        if (role != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("role"), role));
        }
        if (verified != null) {
            specification = specification.and((root, query, builder) -> verified
                    ? builder.isNotNull(root.get("emailVerifiedAt"))
                    : builder.isNull(root.get("emailVerifiedAt")));
        }
        if (hasText(email)) {
            String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("email"), normalizedEmail));
        }

        Page<User> users = userRepository.findAll(specification, adminPage(page, size));
        Map<Long, Wallet> wallets = walletsByUserId(users.getContent().stream().map(User::getId).toList());
        return PageResponse.from(users.map(user -> toUserSummary(user, wallets.get(user.getId()))));
    }

    @Transactional(readOnly = true, timeout = 5)
    public AdminUserDetailResponse user(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Wallet wallet = walletRepository.findByUser_Id(userId).orElse(null);
        AdminUserDetailResponse.VirtualAccountSnapshot virtualAccount = virtualAccountRepository
                .findByUser_Id(userId)
                .map(account -> new AdminUserDetailResponse.VirtualAccountSnapshot(
                        maskAccount(account.getAccountNumber()),
                        account.getBankName(),
                        account.getAccountName(),
                        account.getExpiresAt()
                ))
                .orElse(null);
        AdminUserDetailResponse.BankAccountSnapshot bankAccount = bankAccountRepository
                .findByUser_Id(userId)
                .map(account -> new AdminUserDetailResponse.BankAccountSnapshot(
                        maskLastFour(account.getAccountNumberLast4()),
                        account.getBankCode(),
                        account.getBankName(),
                        account.getAccountName(),
                        account.getUpdatedAt()
                ))
                .orElse(null);

        return new AdminUserDetailResponse(
                toUserSummary(user, wallet),
                virtualAccount,
                bankAccount,
                orderRepository.count((root, query, builder) ->
                        builder.equal(root.get("creator").get("id"), userId)),
                withdrawalRepository.count((root, query, builder) ->
                        builder.equal(root.get("wallet").get("user").get("id"), userId))
        );
    }

    @Transactional(readOnly = true, timeout = 5)
    public PageResponse<AdminOrderSummaryResponse> orders(
            Long creatorId,
            OrderStatus status,
            PaymentStatus paymentStatus,
            SettlementStatus settlementStatus,
            OrderFundingMode fundingMode,
            int page,
            int size
    ) {
        Specification<Order> specification = all();
        if (creatorId != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("creator").get("id"), creatorId));
        }
        if (status != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("status"), status));
        }
        if (paymentStatus != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("paymentStatus"), paymentStatus));
        }
        if (settlementStatus != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("settlementStatus"), settlementStatus));
        }
        if (fundingMode != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("fundingMode"), fundingMode));
        }
        return PageResponse.from(orderRepository
                .findAll(specification, adminPage(page, size))
                .map(this::toOrderSummary));
    }

    @Transactional(readOnly = true, timeout = 5)
    public AdminOrderDetailResponse order(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        OrderCounterparty counterparty = counterpartyRepository.findByOrder_Id(orderId).orElse(null);
        OrderParcel parcel = parcelRepository.findByOrder_Id(orderId).orElse(null);
        OrderShipment shipment = orderShipmentRepository.findByOrder_Id(orderId).orElse(null);
        ShippingQuote selectedQuote = shipment == null ? null : selectedQuote(orderId);
        OrderSettlement settlement = orderSettlementRepository.findByOrder_Id(orderId).orElse(null);

        return new AdminOrderDetailResponse(
                toOrderSummary(order),
                new AdminOrderDetailResponse.PartySnapshot(
                        order.getCreatorFirstName(),
                        order.getCreatorLastName(),
                        maskEmail(order.getCreatorEmail()),
                        maskPhone(order.getCreatorPhoneNumber()),
                        order.getCreatorCity(),
                        order.getCreatorState(),
                        order.getCreatorCountry(),
                        order.getCreator().isEnabled(),
                        order.getCreatorRole() == CreatorRole.VENDOR
                                ? maskLastFour(order.getVendorBankAccountLast4())
                                : null,
                        order.getCreatorRole() == CreatorRole.VENDOR ? order.getVendorBankName() : null,
                        order.getCreatorRole() == CreatorRole.VENDOR ? order.getVendorBankAccountName() : null
                ),
                counterparty == null ? null : new AdminOrderDetailResponse.PartySnapshot(
                        counterparty.getFirstName(),
                        counterparty.getLastName(),
                        maskEmail(counterparty.getEmail()),
                        maskPhone(counterparty.getPhoneNumber()),
                        counterparty.getCity(),
                        counterparty.getState(),
                        counterparty.getCountry(),
                        counterparty.isPhoneVerified(),
                        order.getCreatorRole() == CreatorRole.BUYER
                                ? maskLastFour(order.getVendorBankAccountLast4() != null
                                ? order.getVendorBankAccountLast4()
                                : counterparty.getBankAccountLast4())
                                : null,
                        order.getCreatorRole() == CreatorRole.BUYER
                                ? order.getVendorBankName() != null
                                ? order.getVendorBankName()
                                : counterparty.getBankName()
                                : null,
                        order.getCreatorRole() == CreatorRole.BUYER
                                ? order.getVendorBankAccountName() != null
                                ? order.getVendorBankAccountName()
                                : counterparty.getBankAccountName()
                                : null
                ),
                parcel == null ? null : new AdminOrderDetailResponse.ParcelSnapshot(
                        parcel.getWeightKg(),
                        parcel.getLengthCm(),
                        parcel.getWidthCm(),
                        parcel.getHeightCm(),
                        parcel.getQuantity(),
                        parcel.getParcelType()
                ),
                shipment == null ? null : toShipment(shipment),
                selectedQuote == null ? null : toQuote(selectedQuote),
                settlement == null ? null : toSettlement(settlement),
                paymentAttemptRepository.findByOrder_IdOrderByCreatedAtDesc(orderId, detailPage()).stream()
                        .map(this::toPaymentAttempt)
                        .toList(),
                paymentReceiptRepository.findByOrder_IdOrderByCreatedAtDesc(orderId, detailPage()).stream()
                        .map(this::toPaymentReceipt)
                        .toList(),
                historyRepository.findByOrder_IdOrderByCreatedAtDesc(orderId, detailPage()).stream()
                        .map(history -> new OrderStatusHistoryResponse(
                                history.getFromStatus(),
                                history.getToStatus(),
                                history.getReason(),
                                history.getCreatedAt()
                        ))
                        .toList()
        );
    }

    @Transactional(readOnly = true, timeout = 5)
    public PageResponse<AdminWaybillSummaryResponse> waybills(
            Long creatorId,
            WaybillStatus status,
            WaybillPaymentStatus paymentStatus,
            int page,
            int size
    ) {
        Specification<Waybill> specification = all();
        if (creatorId != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("creator").get("id"), creatorId));
        }
        if (status != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("status"), status));
        }
        if (paymentStatus != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("paymentStatus"), paymentStatus));
        }
        return PageResponse.from(waybillRepository
                .findAll(specification, adminPage(page, size))
                .map(this::toWaybillSummary));
    }

    @Transactional(readOnly = true, timeout = 5)
    public AdminWaybillDetailResponse waybill(Long waybillId) {
        Waybill waybill = waybillRepository.findById(waybillId)
                .orElseThrow(() -> new ResourceNotFoundException("Waybill not found"));
        WaybillParcel parcel = waybillParcelRepository.findByWaybill_Id(waybillId).orElse(null);
        WaybillShipment shipment = waybillShipmentRepository.findByWaybill_Id(waybillId).orElse(null);

        return new AdminWaybillDetailResponse(
                toWaybillSummary(waybill),
                toWaybillCreator(waybill.getCreator()),
                waybillPartyRepository.findByWaybill_IdOrderByRoleAsc(waybillId, detailPage()).stream()
                        .map(this::toWaybillParty)
                        .toList(),
                parcel == null ? null : toWaybillParcel(parcel),
                shipment == null ? null : toWaybillShipment(shipment),
                shipment == null ? List.of() : waybillQuoteRepository
                        .findByShipment_IdOrderByCreatedAtDesc(shipment.getId(), detailPage()).stream()
                        .map(this::toWaybillQuote)
                        .toList(),
                waybillPaymentAttemptRepository
                        .findByWaybill_IdOrderByCreatedAtDesc(waybillId, detailPage()).stream()
                        .map(this::toWaybillPaymentAttempt)
                        .toList(),
                waybillPaymentReceiptRepository
                        .findByWaybill_IdOrderByCreatedAtDesc(waybillId, detailPage()).stream()
                        .map(this::toWaybillPaymentReceipt)
                        .toList(),
                waybillRefundRepository.findByWaybill_IdOrderByCreatedAtDesc(waybillId, detailPage()).stream()
                        .map(this::toWaybillRefund)
                        .toList(),
                waybillInvitationRepository
                        .findByWaybill_IdOrderByCreatedAtDesc(waybillId, detailPage()).stream()
                        .map(this::toWaybillInvitation)
                        .toList(),
                waybillHistoryRepository.findByWaybill_IdOrderByCreatedAtDesc(waybillId, detailPage()).stream()
                        .map(history -> new WaybillStatusHistoryResponse(
                                history.getFromStatus(),
                                history.getToStatus(),
                                history.getReason(),
                                history.getCreatedAt()
                        ))
                        .toList()
        );
    }

    @Transactional(readOnly = true, timeout = 5)
    public PageResponse<AdminWithdrawalResponse> withdrawals(
            Long userId,
            WalletWithdrawalStatus status,
            int page,
            int size
    ) {
        Specification<WalletWithdrawal> specification = all();
        if (userId != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("wallet").get("user").get("id"), userId));
        }
        if (status != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("status"), status));
        }
        return PageResponse.from(withdrawalRepository
                .findAll(specification, adminPage(page, size))
                .map(this::toWithdrawal));
    }

    @Transactional(readOnly = true, timeout = 5)
    public PageResponse<AdminSettlementResponse> settlements(
            Long orderId,
            SettlementStatus status,
            SettlementType settlementType,
            int page,
            int size
    ) {
        Specification<OrderSettlement> specification = all();
        if (orderId != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("order").get("id"), orderId));
        }
        if (status != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("status"), status));
        }
        if (settlementType != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("settlementType"), settlementType));
        }
        return PageResponse.from(settlementRepository
                .findAll(specification, adminPage(page, size))
                .map(this::toSettlement));
    }

    @Transactional(readOnly = true, timeout = 5)
    public PageResponse<AdminShipmentResponse> shipments(
            Long orderId,
            ShipmentStatus status,
            String provider,
            int page,
            int size
    ) {
        Specification<OrderShipment> specification = all();
        if (orderId != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("order").get("id"), orderId));
        }
        if (status != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("status"), status));
        }
        if (hasText(provider)) {
            String normalizedProvider = provider.trim().toLowerCase(Locale.ROOT);
            specification = specification.and((root, query, builder) ->
                    builder.equal(builder.lower(root.get("provider")), normalizedProvider));
        }
        return PageResponse.from(shipmentRepository
                .findAll(specification, adminPage(page, size))
                .map(this::toShipment));
    }

    @Transactional(readOnly = true, timeout = 5)
    public PageResponse<AdminWalletTransactionResponse> walletTransactions(
            Long userId,
            Long orderId,
            WalletTransactionType transactionType,
            int page,
            int size
    ) {
        Specification<WalletTransaction> specification = all();
        if (userId != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("wallet").get("user").get("id"), userId));
        }
        if (orderId != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("order").get("id"), orderId));
        }
        if (transactionType != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("transactionType"), transactionType));
        }
        return PageResponse.from(walletTransactionRepository
                .findAll(specification, adminPage(page, size))
                .map(this::toWalletTransaction));
    }

    @Transactional(readOnly = true, timeout = 5)
    public PageResponse<AdminPaymentAttemptResponse> paymentAttempts(
            Long orderId,
            PaymentAttemptStatus status,
            int page,
            int size
    ) {
        Specification<OrderPaymentAttempt> specification = all();
        if (orderId != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("order").get("id"), orderId));
        }
        if (status != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("status"), status));
        }
        return PageResponse.from(paymentAttemptRepository
                .findAll(specification, adminPage(page, size))
                .map(this::toPaymentAttempt));
    }

    @Transactional(readOnly = true, timeout = 5)
    public PageResponse<AdminPaymentReceiptResponse> paymentReceipts(
            Long orderId,
            PaymentReceiptClassification classification,
            PaymentTransactionStatus providerStatus,
            int page,
            int size
    ) {
        Specification<PaymentReceipt> specification = all();
        if (orderId != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("order").get("id"), orderId));
        }
        if (classification != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("classification"), classification));
        }
        if (providerStatus != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("providerStatus"), providerStatus));
        }
        return PageResponse.from(paymentReceiptRepository
                .findAll(specification, adminPage(page, size))
                .map(this::toPaymentReceipt));
    }

    private AdminUserSummaryResponse toUserSummary(User user, Wallet wallet) {
        return new AdminUserSummaryResponse(
                user.getId(),
                user.getFirstname(),
                user.getLastname(),
                maskEmail(user.getEmail()),
                maskPhone(user.getPhoneNumber()),
                user.getRole(),
                user.isEnabled(),
                user.getEmailVerifiedAt(),
                user.getCreatedAt(),
                wallet == null ? null : wallet.getBalance(),
                wallet == null ? null : wallet.getCurrency()
        );
    }

    private AdminOrderSummaryResponse toOrderSummary(Order order) {
        return new AdminOrderSummaryResponse(
                order.getId(),
                order.getCreator().getId(),
                order.getCreatorFirstName() + " " + order.getCreatorLastName(),
                maskEmail(order.getCreatorEmail()),
                maskPhone(order.getCreatorPhoneNumber()),
                truncate(order.getItemDescription(), 120),
                order.getCreatorRole(),
                order.getFundingMode(),
                order.getStatus(),
                order.getPaymentStatus(),
                order.getSettlementStatus(),
                order.getEscrowReleasePolicy(),
                order.getItemAmount(),
                order.getDeliveryFee(),
                order.getServiceFee(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getFundedAt(),
                order.getDeliveryConfirmedAt(),
                order.getCarrierPickupConfirmedAt(),
                order.getCompletedAt(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private AdminWaybillSummaryResponse toWaybillSummary(Waybill waybill) {
        User creator = waybill.getCreator();
        return new AdminWaybillSummaryResponse(
                waybill.getId(),
                waybill.getPublicId(),
                creator.getId(),
                creator.getFirstname() + " " + creator.getLastname(),
                maskEmail(creator.getEmail()),
                maskPhone(creator.getPhoneNumber()),
                waybill.getCreatorRole(),
                waybill.getPayerType(),
                truncate(waybill.getDescription(), 120),
                waybill.getDeclaredValue(),
                waybill.getCurrency(),
                waybill.getStatus(),
                waybill.getPaymentStatus(),
                waybill.getProviderDeliveryFee(),
                waybill.getDeliveryMarkup(),
                waybill.getDeliveryFee(),
                waybill.getServiceFee(),
                waybill.getTotalAmount(),
                waybill.getPaymentDueAt(),
                waybill.getFundingPaymentReference(),
                waybill.getFundedAt(),
                waybill.getCompletedAt(),
                waybill.getCancellationReason(),
                waybill.getCancellationRequestedAt(),
                waybill.getCreatedAt(),
                waybill.getUpdatedAt()
        );
    }

    private AdminWaybillDetailResponse.CreatorSnapshot toWaybillCreator(User creator) {
        return new AdminWaybillDetailResponse.CreatorSnapshot(
                creator.getId(),
                creator.getFirstname(),
                creator.getLastname(),
                maskEmail(creator.getEmail()),
                maskPhone(creator.getPhoneNumber()),
                creator.isEnabled(),
                creator.getEmailVerifiedAt(),
                creator.getCreatedAt()
        );
    }

    private AdminWaybillDetailResponse.PartySnapshot toWaybillParty(WaybillParty party) {
        return new AdminWaybillDetailResponse.PartySnapshot(
                party.getId(),
                party.getUser() == null ? null : party.getUser().getId(),
                party.getRole(),
                party.getSource(),
                party.getFirstName(),
                party.getLastName(),
                maskEmail(party.getEmail()),
                maskPhone(party.getPhoneNumber()),
                party.getCity(),
                party.getState(),
                party.getCountry(),
                party.getDetailsCompletedAt(),
                party.getContactVerifiedAt(),
                party.getCreatedAt(),
                party.getUpdatedAt()
        );
    }

    private AdminWaybillDetailResponse.ParcelSnapshot toWaybillParcel(WaybillParcel parcel) {
        return new AdminWaybillDetailResponse.ParcelSnapshot(
                parcel.getId(),
                parcel.getWeightKg(),
                parcel.getLengthCm(),
                parcel.getWidthCm(),
                parcel.getHeightCm(),
                parcel.getQuantity(),
                parcel.getParcelType(),
                parcel.getCreatedAt(),
                parcel.getUpdatedAt()
        );
    }

    private AdminWaybillDetailResponse.ShipmentSnapshot toWaybillShipment(WaybillShipment shipment) {
        return new AdminWaybillDetailResponse.ShipmentSnapshot(
                shipment.getId(),
                shipment.getProvider(),
                shipment.getProviderShipmentId(),
                shipment.getStatus(),
                shipment.getTrackingReference(),
                shipment.getBookingAttempt(),
                shipment.getNextBookingAttemptAt(),
                shipment.getFailureReason(),
                shipment.getCancellationAttempt(),
                shipment.getNextCancellationAttemptAt(),
                shipment.getCancellationFailureReason(),
                shipment.getProviderUpdatedAt(),
                shipment.getCreatedAt(),
                shipment.getUpdatedAt()
        );
    }

    private AdminWaybillDetailResponse.ShippingQuoteSnapshot toWaybillQuote(WaybillShippingQuote quote) {
        return new AdminWaybillDetailResponse.ShippingQuoteSnapshot(
                quote.getId(),
                quote.getCarrierName(),
                quote.getServiceDescription(),
                quote.getProviderAmount(),
                quote.getDeliveryMarkup(),
                quote.getDeliveryFee(),
                quote.getServiceFee(),
                quote.getTotalAmount(),
                quote.getCurrency(),
                quote.getPickupEstimate(),
                quote.getDeliveryEstimate(),
                quote.getStatus(),
                quote.getSelectedAt(),
                quote.getExpiresAt(),
                quote.getCreatedAt()
        );
    }

    private AdminWaybillDetailResponse.PaymentAttemptSnapshot toWaybillPaymentAttempt(
            WaybillPaymentAttempt attempt
    ) {
        return new AdminWaybillDetailResponse.PaymentAttemptSnapshot(
                attempt.getId(),
                attempt.getInvitation() == null ? null : attempt.getInvitation().getId(),
                attempt.getPayerType(),
                attempt.getProvider(),
                attempt.getReference(),
                maskEmail(attempt.getCustomerEmail()),
                attempt.getAmount(),
                attempt.getCurrency(),
                attempt.getStatus(),
                attempt.getProviderTransactionId(),
                attempt.getNextRecheckAt(),
                attempt.getFailureReason(),
                attempt.getCreatedAt(),
                attempt.getUpdatedAt(),
                attempt.getCompletedAt()
        );
    }

    private AdminWaybillDetailResponse.PaymentReceiptSnapshot toWaybillPaymentReceipt(
            WaybillPaymentReceipt receipt
    ) {
        return new AdminWaybillDetailResponse.PaymentReceiptSnapshot(
                receipt.getId(),
                receipt.getAttempt().getId(),
                receipt.getProviderEventKey(),
                receipt.getReference(),
                receipt.getAmount(),
                receipt.getCurrency(),
                receipt.getChannel(),
                receipt.getProviderStatus(),
                receipt.getClassification(),
                receipt.getOccurredAt(),
                receipt.getCreatedAt()
        );
    }

    private AdminWaybillDetailResponse.RefundSnapshot toWaybillRefund(WaybillRefund refund) {
        return new AdminWaybillDetailResponse.RefundSnapshot(
                refund.getId(),
                refund.getPaymentAttempt().getId(),
                refund.getProviderRefundId(),
                refund.getProviderRefundReference(),
                refund.getAmount(),
                refund.getCurrency(),
                refund.getStatus(),
                refund.getReason(),
                refund.getFailureReason(),
                refund.getProviderCreatedAt(),
                refund.getProviderUpdatedAt(),
                refund.getCreatedAt(),
                refund.getUpdatedAt(),
                refund.getCompletedAt()
        );
    }

    private AdminWaybillDetailResponse.InvitationSnapshot toWaybillInvitation(WaybillInvitation invitation) {
        return new AdminWaybillDetailResponse.InvitationSnapshot(
                invitation.getId(),
                invitation.getKind(),
                invitation.getTargetPartyRole(),
                invitation.isCanPay(),
                invitation.getStagedFirstName(),
                invitation.getStagedLastName(),
                maskEmail(invitation.getStagedEmail()),
                maskPhone(invitation.getStagedPhoneNumber()),
                invitation.getOtpSendCount(),
                invitation.getOtpFailedAttemptCount(),
                invitation.getOtpLockedAt(),
                invitation.getExpiresAt(),
                invitation.getVerifiedAt(),
                invitation.getDetailsCompletedAt(),
                invitation.getPaymentCompletedAt(),
                invitation.getRevokedAt(),
                invitation.getCreatedAt(),
                invitation.getUpdatedAt()
        );
    }

    private AdminWithdrawalResponse toWithdrawal(WalletWithdrawal withdrawal) {
        User user = withdrawal.getWallet().getUser();
        return new AdminWithdrawalResponse(
                withdrawal.getId(),
                user.getId(),
                maskEmail(user.getEmail()),
                withdrawal.getAmount(),
                withdrawal.getCurrency(),
                withdrawal.getStatus(),
                withdrawal.getReference(),
                withdrawal.getTransferId(),
                maskLastFour(withdrawal.getAccountNumberLast4()),
                withdrawal.getBankName(),
                withdrawal.getAccountName(),
                withdrawal.getDispatchAttempt(),
                withdrawal.getFailureReason(),
                withdrawal.getProviderUpdatedAt(),
                withdrawal.getCreatedAt(),
                withdrawal.getUpdatedAt(),
                withdrawal.getCompletedAt()
        );
    }

    private AdminSettlementResponse toSettlement(OrderSettlement settlement) {
        return new AdminSettlementResponse(
                settlement.getId(),
                settlement.getOrder().getId(),
                settlement.getSettlementType(),
                settlement.getAmount(),
                settlement.getOrder().getCurrency(),
                settlement.getStatus(),
                settlement.getTrigger(),
                settlement.getTriggeredAt(),
                settlement.getPayoutReference(),
                settlement.getTransferId(),
                settlement.getUnexpectedPayoutReference(),
                settlement.getUnexpectedTransferId(),
                settlement.getPayoutAttempt(),
                settlement.getFailureReason(),
                settlement.getProviderUpdatedAt(),
                settlement.getNextReconciliationAt(),
                settlement.getCreatedAt(),
                settlement.getUpdatedAt(),
                settlement.getCompletedAt()
        );
    }

    private AdminShipmentResponse toShipment(OrderShipment shipment) {
        return new AdminShipmentResponse(
                shipment.getId(),
                shipment.getOrder().getId(),
                shipment.getProvider(),
                shipment.getProviderShipmentId(),
                shipment.getStatus(),
                shipment.getTrackingReference(),
                shipment.getBookingAttempt(),
                shipment.getNextBookingAttemptAt(),
                shipment.getFailureReason(),
                shipment.getProviderUpdatedAt(),
                shipment.getCreatedAt(),
                shipment.getUpdatedAt()
        );
    }

    private AdminWalletTransactionResponse toWalletTransaction(WalletTransaction transaction) {
        User user = transaction.getWallet().getUser();
        return new AdminWalletTransactionResponse(
                transaction.getId(),
                user.getId(),
                maskEmail(user.getEmail()),
                transaction.getOrder() == null ? null : transaction.getOrder().getId(),
                transaction.getWithdrawal() == null ? null : transaction.getWithdrawal().getId(),
                transaction.getPaymentReceipt() == null ? null : transaction.getPaymentReceipt().getId(),
                transaction.getTransactionType(),
                transaction.getReference(),
                transaction.getAmount(),
                transaction.getBalanceBefore(),
                transaction.getBalanceAfter(),
                transaction.getCreatedAt()
        );
    }

    private AdminPaymentAttemptResponse toPaymentAttempt(OrderPaymentAttempt attempt) {
        return new AdminPaymentAttemptResponse(
                attempt.getId(),
                attempt.getOrder().getId(),
                attempt.getProvider(),
                attempt.getProviderReference(),
                attempt.getAmount(),
                attempt.getCurrency(),
                attempt.getStatus(),
                attempt.getCreatedAt(),
                attempt.getUpdatedAt(),
                attempt.getCompletedAt()
        );
    }

    private AdminPaymentReceiptResponse toPaymentReceipt(PaymentReceipt receipt) {
        return new AdminPaymentReceiptResponse(
                receipt.getId(),
                receipt.getOrder() == null ? null : receipt.getOrder().getId(),
                receipt.getProviderEventKey(),
                receipt.getProviderReference(),
                maskAccount(receipt.getReceiverAccountNumber()),
                receipt.getAmount(),
                receipt.getCurrency(),
                receipt.getChannel(),
                receipt.getProviderStatus(),
                receipt.getClassification(),
                receipt.getOccurredAt(),
                receipt.getCreatedAt()
        );
    }

    private AdminOrderDetailResponse.ShippingQuoteSnapshot toQuote(ShippingQuote quote) {
        return new AdminOrderDetailResponse.ShippingQuoteSnapshot(
                quote.getId(),
                quote.getCarrierName(),
                quote.getServiceDescription(),
                quote.getProviderAmount(),
                quote.getDeliveryMarkup(),
                quote.getDeliveryFee(),
                quote.getServiceFee(),
                quote.getBuyerTotal(),
                quote.getCurrency(),
                quote.getPickupEstimate(),
                quote.getDeliveryEstimate(),
                quote.getStatus(),
                quote.getSelectedAt(),
                quote.getExpiresAt()
        );
    }

    private ShippingQuote selectedQuote(Long orderId) {
        return shippingQuoteRepository.findByShipment_Order_IdAndStatus(orderId, ShippingQuoteStatus.USED)
                .or(() -> shippingQuoteRepository.findByShipment_Order_IdAndStatus(
                        orderId,
                        ShippingQuoteStatus.SELECTED
                ))
                .orElse(null);
    }

    private Map<Long, Wallet> walletsByUserId(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return walletRepository.findByUser_IdIn(userIds).stream()
                .collect(Collectors.toMap(wallet -> wallet.getUser().getId(), Function.identity()));
    }

    private <T> Specification<T> all() {
        return (root, query, builder) -> builder.conjunction();
    }

    private PageRequest adminPage(int page, int size) {
        Pagination.newestFirst(page, size);
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("id")));
    }

    private PageRequest detailPage() {
        return PageRequest.of(0, MAX_DETAIL_RECORDS);
    }

    private String maskEmail(String email) {
        if (!hasText(email)) {
            return null;
        }
        int separator = email.indexOf('@');
        if (separator <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(separator);
    }

    private String maskPhone(String phoneNumber) {
        if (!hasText(phoneNumber)) {
            return null;
        }
        String value = phoneNumber.trim();
        return value.length() <= 4 ? "******" : "******" + value.substring(value.length() - 4);
    }

    private String maskAccount(String accountNumber) {
        if (!hasText(accountNumber)) {
            return null;
        }
        return accountNumber.length() <= 4
                ? "******"
                : maskLastFour(accountNumber.substring(accountNumber.length() - 4));
    }

    private String maskLastFour(String lastFour) {
        if (!hasText(lastFour)) {
            return null;
        }
        String value = lastFour.trim();
        return value.length() < 4 ? "******" : "******" + value.substring(value.length() - 4);
    }

    private String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value;
        }
        return value.substring(0, maximumLength);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
