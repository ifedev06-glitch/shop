package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.*;
import com.example.shopsecure.dto.response.*;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.event.CounterpartyVerifiedEvent;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.OtpVerificationException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.provider.payment.*;
import com.example.shopsecure.repository.*;
import com.example.shopsecure.util.NigerianPhoneNumber;
import com.example.shopsecure.util.Pagination;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderCounterpartyRepository counterpartyRepository;
    private final OrderParcelRepository parcelRepository;
    private final OrderShipmentRepository shipmentRepository;
    private final ShippingQuoteRepository quoteRepository;
    private final UserRepository userRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final OtpService otpService;
    private final OtpDeliveryService otpDeliveryService;
    private final GuestOrderAccessService guestAccessService;
    private final BankAccountService bankAccountService;
    private final SensitiveDataCipher dataCipher;
    private final PayoutProvider payoutProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final OrderStatusService orderStatusService;
    private final OrderTrackingService trackingService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    @Transactional
    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        User creator = currentUser();
        requireCompleteAccount(creator);
        if (request.creatorRole() == CreatorRole.BUYER) {
            requireVirtualAccount(creator);
        }

        ResolvedBankAccount resolvedBank = bankAccountService.resolve(
                request.vendorBankAccount().accountNumber(),
                request.vendorBankAccount().bankCode()
        );
        PayoutRecipientResponse payoutRecipient = payoutProvider.createPayoutRecipient(
                new PayoutRecipientRequest(
                        resolvedBank.accountName(),
                        resolvedBank.accountNumber(),
                        resolvedBank.bankCode()
                )
        );

        Instant now = Instant.now();
        Order order = Order.builder()
                .itemAmount(request.amount())
                .itemDescription(request.itemDescription().trim())
                .creatorFirstName(creator.getFirstname().trim())
                .creatorLastName(creator.getLastname().trim())
                .creatorPhoneNumber(NigerianPhoneNumber.normalize(creator.getPhoneNumber()))
                .creatorEmail(creator.getEmail())
                .creatorAddressLine1(request.creatorAddress().line1().trim())
                .creatorAddressLine2(trimToEmpty(request.creatorAddress().line2()))
                .creatorCity(request.creatorAddress().city().trim())
                .creatorState(request.creatorAddress().state().trim())
                .creatorCountry(request.creatorAddress().country().toUpperCase(Locale.ROOT))
                .creatorPostalCode(request.creatorAddress().postalCode().trim())
                .creatorRole(request.creatorRole())
                .fundingMode(request.creatorRole() == CreatorRole.BUYER
                        ? OrderFundingMode.WALLET
                        : OrderFundingMode.PAYSTACK_CHECKOUT)
                .escrowReleasePolicy(EscrowReleasePolicy.BUYER_DELIVERY_CONFIRMATION)
                .status(OrderStatus.AWAITING_SHIPPING_RATE)
                .shareToken(generateUniqueShareToken())
                .vendorBankAccountLast4(lastFour(resolvedBank.accountNumber()))
                .vendorBankCode(resolvedBank.bankCode())
                .vendorBankName(resolvedBank.bankName())
                .vendorBankAccountName(resolvedBank.accountName())
                .vendorPayoutRecipientCode(payoutRecipient.recipientId())
                .creator(creator)
                .createdAt(LocalDateTime.now())
                .updatedAt(now)
                .build();
        order = orderRepository.save(order);
        saveCounterparty(order, request.counterparty(), now);
        saveParcel(order, request.parcel());
        orderStatusService.recordInitial(order, "Order created by " + roleName(order.getCreatorRole()));

        return new CreateOrderResponse(
                order.getId(),
                order.getItemAmount(),
                order.getCurrency(),
                order.getItemDescription(),
                order.getCreatorRole(),
                order.getCreatorRole().opposite(),
                order.getFundingMode(),
                order.getEscrowReleasePolicy(),
                order.getStatus(),
                order.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public PublicOrderResponse getByShareToken(String shareToken) {
        Order order = findByShareToken(shareToken);
        requireBuyerPaymentOrder(order);
        boolean verified = counterpartyRepository.findByOrder_Id(order.getId())
                .map(OrderCounterparty::isPhoneVerified)
                .orElse(false);
        ShippingQuote selectedQuote = quoteRepository.findByShipment_Order_IdAndStatus(
                order.getId(),
                ShippingQuoteStatus.SELECTED
        ).orElseGet(() -> quoteRepository.findByShipment_Order_IdAndStatus(
                order.getId(),
                ShippingQuoteStatus.USED
        ).orElse(null));
        return new PublicOrderResponse(
                order.getItemAmount(),
                order.getItemDescription(),
                order.getCreatorFirstName() + " " + order.getCreatorLastName(),
                order.getCurrency(),
                order.getStatus(),
                order.getCreatorRole(),
                order.getCreatorRole().opposite(),
                verified,
                order.getPaymentStatus(),
                order.getSettlementStatus(),
                order.getEscrowReleasePolicy(),
                pricing(order),
                selectedQuote == null ? null : new ShippingQuoteResponse(
                        selectedQuote.getId(),
                        selectedQuote.getCarrierName(),
                        selectedQuote.getCarrierLogo(),
                        selectedQuote.getServiceDescription(),
                        selectedQuote.getDeliveryFee(),
                        selectedQuote.getServiceFee(),
                        selectedQuote.getBuyerTotal(),
                        selectedQuote.getCurrency(),
                        selectedQuote.getPickupEstimate(),
                        selectedQuote.getDeliveryEstimate(),
                        selectedQuote.getExpiresAt()
                )
        );
    }

    @Transactional
    public OtpChallengeResponse requestBuyerPaymentAccess(String shareToken) {
        Order order = findByShareTokenForUpdate(shareToken);
        requireBuyerPaymentOrder(order);
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            throw new BusinessException("Order is not awaiting buyer payment");
        }
        requireActiveSelectedQuote(order);
        OrderCounterparty buyer = counterpartyRepository.findByOrderIdForUpdate(order.getId())
                .orElseThrow(() -> new BusinessException("Buyer details are not available"));
        if (buyer.isPhoneVerified()) {
            throw new BusinessException("Buyer payment access is already verified; use access recovery");
        }
        OtpService.IssuedOtp issued = otpService.issue(
                OtpPurpose.ORDER_COUNTERPARTY,
                order.getShareToken(),
                buyer.getPhoneNumber()
        );
        otpDeliveryService.send(
                buyer.getPhoneNumber(),
                buyer.getEmail(),
                issued.code(),
                OtpPurpose.ORDER_COUNTERPARTY
        );
        return challengeResponse(issued, CreatorRole.BUYER, buyer.getPhoneNumber());
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public OrderVerificationResponse verifyBuyerPaymentOtp(
            String shareToken,
            String challengeId,
            String code
    ) {
        Order order = findByShareTokenForUpdate(shareToken);
        requireBuyerPaymentOrder(order);
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            throw new BusinessException("Order is not awaiting buyer payment");
        }
        requireActiveSelectedQuote(order);
        OrderCounterparty buyer = counterpartyRepository.findByOrderIdForUpdate(order.getId())
                .orElseThrow(() -> new BusinessException("Buyer details are not available"));
        OtpChallenge challenge = otpService.requireChallenge(
                challengeId,
                OtpPurpose.ORDER_COUNTERPARTY,
                order.getShareToken()
        );
        if (!challenge.getPhoneNumber().equals(buyer.getPhoneNumber())) {
            throw new OtpVerificationException("Verification challenge does not match the buyer");
        }
        otpService.verify(OtpPurpose.ORDER_COUNTERPARTY, order.getShareToken(), challengeId, code);
        buyer.setPhoneVerified(true);
        buyer.setUpdatedAt(Instant.now());
        counterpartyRepository.save(buyer);
        GuestOrderAccessService.IssuedGuestToken guestToken = guestAccessService.issue(order, buyer);
        return new OrderVerificationResponse(
                order.getStatus(),
                CreatorRole.BUYER,
                true,
                guestToken.token(),
                guestToken.expiresAt()
        );
    }

    @Transactional
    public OtpChallengeResponse resendBuyerPaymentOtp(String shareToken, String challengeId) {
        Order order = findByShareTokenForUpdate(shareToken);
        requireBuyerPaymentOrder(order);
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            throw new BusinessException("Order is not awaiting buyer payment");
        }
        requireActiveSelectedQuote(order);
        otpService.requireChallenge(challengeId, OtpPurpose.ORDER_COUNTERPARTY, order.getShareToken());
        OrderCounterparty buyer = counterpartyRepository.findByOrderIdForUpdate(order.getId())
                .orElseThrow(() -> new BusinessException("Buyer details are not available"));
        if (buyer.isPhoneVerified()) {
            throw new BusinessException("Buyer payment access is already verified; use access recovery");
        }
        OtpService.IssuedOtp issued = otpService.issue(
                OtpPurpose.ORDER_COUNTERPARTY,
                order.getShareToken(),
                buyer.getPhoneNumber()
        );
        otpDeliveryService.send(
                buyer.getPhoneNumber(),
                buyer.getEmail(),
                issued.code(),
                OtpPurpose.ORDER_COUNTERPARTY
        );
        return challengeResponse(issued, CreatorRole.BUYER, buyer.getPhoneNumber());
    }

    @Transactional(readOnly = true)
    public ResolvedBankAccountResponse resolveGuestVendorBank(
            String shareToken,
            ResolveBankAccountRequest request
    ) {
        Order order = findByShareToken(shareToken);
        if (order.getStatus() != OrderStatus.AWAITING_COUNTERPARTY
                || order.getCreatorRole().opposite() != CreatorRole.VENDOR) {
            throw new BusinessException("This order is not accepting vendor payout details");
        }
        return bankAccountService.resolve(request);
    }

    @Transactional(readOnly = true)
    public OrderDetailsResponse getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        requireOwner(order);
        return details(order);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderDetailsResponse> getOrders(int page, int size) {
        User creator = currentUser();
        return PageResponse.from(orderRepository.findByCreator_Id(
                creator.getId(),
                Pagination.newestFirst(page, size)
        ).map(this::details));
    }

    @Transactional(readOnly = true)
    public SellerPendingAmountResponse getSellerPendingAmount() {
        User seller = currentUser();
        OrderRepository.SellerPendingAmountProjection summary = orderRepository
                .summarizeSellerPendingAmount(
                        seller.getId(),
                        CreatorRole.VENDOR,
                        CreatorRole.BUYER,
                        EnumSet.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED),
                        SettlementStatus.COMPLETED,
                        PaymentStatus.CONFIRMED
                );
        BigDecimal pending = amountOrZero(summary == null ? null : summary.getPendingAmount());
        BigDecimal funded = amountOrZero(summary == null ? null : summary.getFundedPendingAmount());
        return new SellerPendingAmountResponse(
                pending,
                funded,
                pending.subtract(funded),
                countOrZero(summary == null ? null : summary.getActiveOrderCount()),
                countOrZero(summary == null ? null : summary.getFundedOrderCount()),
                "NGN",
                Instant.now()
        );
    }

    @Transactional(readOnly = true)
    public List<OrderStatusHistoryResponse> getHistory(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        requireOwner(order);
        return historyRepository.findByOrder_IdOrderByCreatedAtAsc(orderId).stream()
                .map(history -> new OrderStatusHistoryResponse(
                        history.getFromStatus(),
                        history.getToStatus(),
                        history.getReason(),
                        history.getCreatedAt()
                ))
                .toList();
    }

    @Transactional
    public OtpChallengeResponse submitCounterpartyInfo(String shareToken, CounterpartyInfoRequest request) {
        Order order = findByShareTokenForUpdate(shareToken);
        if (order.getStatus() != OrderStatus.AWAITING_COUNTERPARTY) {
            throw new BusinessException("Counterparty information can no longer be changed");
        }
        CreatorRole counterpartyRole = order.getCreatorRole().opposite();
        validateCounterpartyData(counterpartyRole, request);

        ResolvedBankAccount resolvedBank = null;
        if (counterpartyRole == CreatorRole.VENDOR) {
            resolvedBank = bankAccountService.resolve(
                    request.externalBankAccount().accountNumber(),
                    request.externalBankAccount().bankCode()
            );
        }

        OrderCounterparty counterparty = counterpartyRepository.findByOrder_Id(order.getId())
                .orElseGet(() -> OrderCounterparty.builder()
                        .order(order)
                        .createdAt(LocalDateTime.now())
                        .build());
        counterparty.setUser(null);
        counterparty.setFirstName(request.firstName().trim());
        counterparty.setLastName(request.lastName().trim());
        counterparty.setPhoneNumber(NigerianPhoneNumber.normalize(request.phoneNumber()));
        counterparty.setEmail(request.email().trim().toLowerCase(Locale.ROOT));
        counterparty.setAddressLine1(request.address().line1().trim());
        counterparty.setAddressLine2(trimToEmpty(request.address().line2()));
        counterparty.setCity(request.address().city().trim());
        counterparty.setState(request.address().state().trim());
        counterparty.setCountry(request.address().country().toUpperCase(Locale.ROOT));
        counterparty.setPostalCode(request.address().postalCode().trim());
        counterparty.setPhoneVerified(false);
        counterparty.setUpdatedAt(Instant.now());
        if (resolvedBank != null) {
            String accountNumber = resolvedBank.accountNumber();
            counterparty.setBankAccountEncrypted(dataCipher.encrypt(accountNumber));
            counterparty.setBankAccountLast4(accountNumber.substring(accountNumber.length() - 4));
            counterparty.setBankCode(resolvedBank.bankCode());
            counterparty.setBankName(resolvedBank.bankName());
            counterparty.setBankAccountName(resolvedBank.accountName());
            counterparty.setPayoutRecipientCode(null);
        } else {
            clearBankDetails(counterparty);
        }
        counterpartyRepository.save(counterparty);

        if (counterpartyRole == CreatorRole.VENDOR) {
            saveParcel(order, request.parcel());
        }

        OtpService.IssuedOtp issued = otpService.issue(
                OtpPurpose.ORDER_COUNTERPARTY,
                order.getShareToken(),
                counterparty.getPhoneNumber()
        );
        otpDeliveryService.send(
                counterparty.getPhoneNumber(),
                counterparty.getEmail(),
                issued.code(),
                OtpPurpose.ORDER_COUNTERPARTY
        );
        order.setUpdatedAt(Instant.now());
        orderRepository.save(order);
        return challengeResponse(issued, counterpartyRole, counterparty.getPhoneNumber());
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public OrderVerificationResponse verifyCounterpartyOtp(
            String shareToken,
            String challengeId,
            String code
    ) {
        Order order = findByShareTokenForUpdate(shareToken);
        if (order.getStatus() != OrderStatus.AWAITING_COUNTERPARTY) {
            throw new BusinessException("Order is not awaiting counterparty verification");
        }
        OrderCounterparty counterparty = counterpartyRepository.findByOrderIdForUpdate(order.getId())
                .orElseThrow(() -> new BusinessException("Counterparty information must be submitted first"));
        OtpChallenge challenge = otpService.requireChallenge(
                challengeId,
                OtpPurpose.ORDER_COUNTERPARTY,
                order.getShareToken()
        );
        if (!challenge.getPhoneNumber().equals(counterparty.getPhoneNumber())) {
            throw new OtpVerificationException("Verification challenge does not match current contact details");
        }
        otpService.verify(OtpPurpose.ORDER_COUNTERPARTY, order.getShareToken(), challengeId, code);

        CreatorRole counterpartyRole = order.getCreatorRole().opposite();
        if (counterpartyRole == CreatorRole.VENDOR && counterparty.getPayoutRecipientCode() == null) {
            PayoutRecipientResponse recipient = payoutProvider.createPayoutRecipient(
                    new PayoutRecipientRequest(
                            counterparty.getBankAccountName(),
                            dataCipher.decrypt(counterparty.getBankAccountEncrypted()),
                            counterparty.getBankCode()
                    )
            );
            counterparty.setPayoutRecipientCode(recipient.recipientId());
            counterparty.setBankAccountEncrypted(null);
        }
        counterparty.setPhoneVerified(true);
        counterparty.setUpdatedAt(Instant.now());
        counterpartyRepository.save(counterparty);

        orderStatusService.transition(
                order,
                OrderStatus.AWAITING_SHIPPING_RATE,
                roleName(counterpartyRole) + " contact details verified"
        );
        orderRepository.save(order);
        GuestOrderAccessService.IssuedGuestToken guestToken = guestAccessService.issue(order, counterparty);
        eventPublisher.publishEvent(new CounterpartyVerifiedEvent(
                order.getCreator().getEmail(),
                order.getCreator().getFirstname(),
                order.getItemDescription(),
                counterpartyRole
        ));
        return new OrderVerificationResponse(
                order.getStatus(),
                counterpartyRole,
                true,
                guestToken.token(),
                guestToken.expiresAt()
        );
    }

    @Transactional
    public OtpChallengeResponse resendCounterpartyOtp(String shareToken, String challengeId) {
        Order order = findByShareTokenForUpdate(shareToken);
        if (order.getStatus() != OrderStatus.AWAITING_COUNTERPARTY) {
            throw new BusinessException("Order is not awaiting counterparty verification");
        }
        otpService.requireChallenge(challengeId, OtpPurpose.ORDER_COUNTERPARTY, order.getShareToken());
        OrderCounterparty counterparty = counterpartyRepository.findByOrder_Id(order.getId())
                .orElseThrow(() -> new BusinessException("Counterparty information must be submitted first"));
        OtpService.IssuedOtp issued = otpService.issue(
                OtpPurpose.ORDER_COUNTERPARTY,
                order.getShareToken(),
                counterparty.getPhoneNumber()
        );
        otpDeliveryService.send(
                counterparty.getPhoneNumber(),
                counterparty.getEmail(),
                issued.code(),
                OtpPurpose.ORDER_COUNTERPARTY
        );
        return challengeResponse(
                issued,
                order.getCreatorRole().opposite(),
                counterparty.getPhoneNumber()
        );
    }

    @Transactional
    public OtpChallengeResponse requestGuestAccessRecovery(String shareToken) {
        Order order = findByShareTokenForUpdate(shareToken);
        requireBuyerPaymentOrder(order);
        if (order.getStatus() == OrderStatus.COMPLETED || order.getStatus() == OrderStatus.CANCELLED) {
            throw new BusinessException("Guest access can no longer be recovered for this order");
        }
        OrderCounterparty counterparty = counterpartyRepository.findByOrder_Id(order.getId())
                .filter(OrderCounterparty::isPhoneVerified)
                .orElseThrow(() -> new BusinessException("Counterparty must verify their details first"));
        OtpService.IssuedOtp issued = otpService.issue(
                OtpPurpose.ORDER_ACCESS_RECOVERY,
                accessRecoverySubject(order),
                counterparty.getPhoneNumber()
        );
        otpDeliveryService.send(
                counterparty.getPhoneNumber(),
                counterparty.getEmail(),
                issued.code(),
                OtpPurpose.ORDER_ACCESS_RECOVERY
        );
        return challengeResponse(
                issued,
                order.getCreatorRole().opposite(),
                counterparty.getPhoneNumber()
        );
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public OrderVerificationResponse recoverGuestAccess(
            String shareToken,
            String challengeId,
            String code
    ) {
        Order order = findByShareTokenForUpdate(shareToken);
        requireBuyerPaymentOrder(order);
        if (order.getStatus() == OrderStatus.COMPLETED || order.getStatus() == OrderStatus.CANCELLED) {
            throw new BusinessException("Guest access can no longer be recovered for this order");
        }
        OrderCounterparty counterparty = counterpartyRepository.findByOrder_Id(order.getId())
                .filter(OrderCounterparty::isPhoneVerified)
                .orElseThrow(() -> new BusinessException("Counterparty must verify their details first"));
        OtpChallenge challenge = otpService.requireChallenge(
                challengeId,
                OtpPurpose.ORDER_ACCESS_RECOVERY,
                accessRecoverySubject(order)
        );
        if (!challenge.getPhoneNumber().equals(counterparty.getPhoneNumber())) {
            throw new OtpVerificationException("Verification challenge does not match current contact details");
        }
        otpService.verify(
                OtpPurpose.ORDER_ACCESS_RECOVERY,
                accessRecoverySubject(order),
                challengeId,
                code
        );
        GuestOrderAccessService.IssuedGuestToken token = guestAccessService.issue(order, counterparty);
        return new OrderVerificationResponse(
                order.getStatus(),
                order.getCreatorRole().opposite(),
                true,
                token.token(),
                token.expiresAt()
        );
    }

    public User currentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    public void requireOwner(Order order) {
        if (!order.getCreator().getId().equals(currentUser().getId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Order does not belong to current user"
            );
        }
    }

    private OrderDetailsResponse details(Order order) {
        OrderCounterparty counterparty = counterpartyRepository.findByOrder_Id(order.getId()).orElse(null);
        OrderParcel parcel = parcelRepository.findByOrder_Id(order.getId()).orElse(null);
        OrderShipment shipment = shipmentRepository.findByOrder_Id(order.getId()).orElse(null);
        ShippingQuote quote = quoteRepository.findByShipment_Order_IdAndStatus(
                order.getId(),
                ShippingQuoteStatus.SELECTED
        ).orElseGet(() -> quoteRepository.findByShipment_Order_IdAndStatus(
                order.getId(),
                ShippingQuoteStatus.USED
        ).orElse(null));
        return new OrderDetailsResponse(
                order.getId(),
                buyerPaymentLink(order),
                order.getItemDescription(),
                order.getItemAmount(),
                order.getCurrency(),
                order.getCreatorRole(),
                order.getCreatorRole().opposite(),
                order.getFundingMode(),
                order.getStatus(),
                order.getPaymentStatus(),
                order.getSettlementStatus(),
                order.getEscrowReleasePolicy(),
                pricing(order),
                showDirectPaymentInstructions(order) ? paymentInstructions(order) : null,
                counterparty(counterparty),
                vendorPayoutAccount(order),
                parcel(parcel),
                shipping(shipment, quote),
                order.getFundedAt(),
                order.getDeliveryConfirmedAt(),
                order.getCarrierPickupConfirmedAt(),
                order.getCompletedAt(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private PricingBreakdownResponse pricing(Order order) {
        if (order.getTotalAmount() == null) {
            return null;
        }
        return new PricingBreakdownResponse(
                order.getItemAmount(),
                order.getDeliveryFee(),
                order.getServiceFee(),
                order.getTotalAmount(),
                order.getCurrency()
        );
    }

    private PaymentInstructionsResponse paymentInstructions(Order order) {
        if (order.getTotalAmount() == null || order.getPaymentReference() == null) {
            return null;
        }
        VirtualAccount account = virtualAccountRepository.findByUser_Id(order.getCreator().getId())
                .orElseThrow(() -> new BusinessException("Collection virtual account is not available"));
        return new PaymentInstructionsResponse(
                toVirtualAccountResponse(account),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getPaymentReference(),
                "SHOPSECURE " + order.getPaymentReference()
        );
    }

    private boolean showDirectPaymentInstructions(Order order) {
        return order.getCreatorRole() == CreatorRole.BUYER
                && order.getFundingMode() == OrderFundingMode.DIRECT_DVA
                && order.getStatus() == OrderStatus.AWAITING_PAYMENT
                && order.getPaymentStatus() != PaymentStatus.OVERPAID;
    }

    private CounterpartyResponse counterparty(OrderCounterparty counterparty) {
        if (counterparty == null) {
            return null;
        }
        return new CounterpartyResponse(
                counterparty.getFirstName(),
                counterparty.getLastName(),
                counterparty.getEmail(),
                counterparty.getPhoneNumber(),
                new AddressResponse(
                        counterparty.getAddressLine1(),
                        counterparty.getAddressLine2(),
                        counterparty.getCity(),
                        counterparty.getState(),
                        counterparty.getCountry(),
                        counterparty.getPostalCode()
                ),
                counterparty.getBankAccountLast4(),
                counterparty.getBankAccountName(),
                counterparty.getBankName(),
                counterparty.isPhoneVerified()
        );
    }

    private ParcelResponse parcel(OrderParcel parcel) {
        return parcel == null ? null : new ParcelResponse(
                parcel.getWeightKg(),
                parcel.getLengthCm(),
                parcel.getWidthCm(),
                parcel.getHeightCm()
        );
    }

    private ShippingStatusResponse shipping(OrderShipment shipment, ShippingQuote quote) {
        if (shipment == null) {
            return null;
        }
        OrderTrackingService.TrackingLink trackingLink = trackingService.linkForOrder(
                shipment.getOrder().getId()
        );
        return new ShippingStatusResponse(
                shipment.getStatus(),
                quote == null ? null : quote.getCarrierName(),
                shipment.getTrackingReference(),
                shipment.getTrackingUrl(),
                trackingLink == null ? null : trackingLink.url(),
                trackingLink == null ? null : trackingLink.availableAt(),
                quote == null ? null : quote.getDeliveryEstimate(),
                shipment.getUpdatedAt()
        );
    }

    private void validateCounterpartyData(CreatorRole role, CounterpartyInfoRequest request) {
        if (role == CreatorRole.VENDOR) {
            if (request.externalBankAccount() == null) {
                throw new BusinessException("Vendor payout bank details are required");
            }
            if (request.parcel() == null) {
                throw new BusinessException("Vendor parcel details are required");
            }
        } else if (request.externalBankAccount() != null || request.parcel() != null) {
            throw new BusinessException("A buyer must not submit payout bank or parcel details");
        }
    }

    private void saveParcel(Order order, OrderParcelRequest request) {
        OrderParcel parcel = parcelRepository.findByOrder_Id(order.getId())
                .orElseGet(() -> OrderParcel.builder()
                        .order(order)
                        .quantity(1)
                        .parcelType("parcel")
                        .createdAt(Instant.now())
                        .build());
        parcel.setWeightKg(request.weightKg());
        parcel.setLengthCm(request.lengthCm());
        parcel.setWidthCm(request.widthCm());
        parcel.setHeightCm(request.heightCm());
        parcelRepository.save(parcel);
    }

    private void saveCounterparty(Order order, OrderPartyRequest request, Instant now) {
        OrderCounterparty counterparty = OrderCounterparty.builder()
                .order(order)
                .user(null)
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .phoneNumber(NigerianPhoneNumber.normalize(request.phoneNumber()))
                .email(request.email().trim().toLowerCase(Locale.ROOT))
                .addressLine1(request.address().line1().trim())
                .addressLine2(trimToEmpty(request.address().line2()))
                .city(request.address().city().trim())
                .state(request.address().state().trim())
                .country(request.address().country().toUpperCase(Locale.ROOT))
                .postalCode(request.address().postalCode().trim())
                .phoneVerified(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(now)
                .build();
        counterpartyRepository.save(counterparty);
    }

    private VendorPayoutAccountResponse vendorPayoutAccount(Order order) {
        if (order.getVendorBankAccountLast4() == null) {
            return null;
        }
        return new VendorPayoutAccountResponse(
                order.getVendorBankAccountLast4(),
                order.getVendorBankAccountName(),
                order.getVendorBankName()
        );
    }

    private void clearBankDetails(OrderCounterparty counterparty) {
        counterparty.setBankAccountEncrypted(null);
        counterparty.setBankAccountLast4(null);
        counterparty.setBankCode(null);
        counterparty.setBankName(null);
        counterparty.setBankAccountName(null);
        counterparty.setPayoutRecipientCode(null);
    }

    private OtpChallengeResponse challengeResponse(
            OtpService.IssuedOtp issued,
            CreatorRole role,
            String phoneNumber
    ) {
        return new OtpChallengeResponse(
                issued.challengeId(),
                role,
                maskPhone(phoneNumber),
                issued.expiresInSeconds()
        );
    }

    private Order findByShareToken(String shareToken) {
        return orderRepository.findByShareToken(shareToken)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    private Order findByShareTokenForUpdate(String shareToken) {
        return orderRepository.findByShareTokenForUpdate(shareToken)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    private void requireCompleteAccount(User user) {
        if (user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) {
            throw new BusinessException("Account phone number is required before creating an order");
        }
        if (user.getFirstname() == null || user.getFirstname().isBlank()
                || user.getLastname() == null || user.getLastname().isBlank()) {
            throw new BusinessException("Account name is required before creating an order");
        }
    }

    private void requireVirtualAccount(User user) {
        if (virtualAccountRepository.findByUser_Id(user.getId()).isEmpty()) {
            throw new BusinessException("Collection virtual account is not available");
        }
    }

    private VirtualAccountDetailsResponse toVirtualAccountResponse(VirtualAccount account) {
        return new VirtualAccountDetailsResponse(
                account.getAccountNumber(),
                account.getBankName(),
                account.getAccountName(),
                account.getExpiresAt()
        );
    }

    private String roleName(CreatorRole role) {
        return role == CreatorRole.VENDOR ? "vendor" : "buyer";
    }

    private BigDecimal amountOrZero(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO.setScale(2) : amount.setScale(2);
    }

    private long countOrZero(Long count) {
        return count == null ? 0 : count;
    }

    private String generateUniqueShareToken() {
        for (int attempt = 0; attempt < 5; attempt++) {
            byte[] bytes = new byte[32];
            secureRandom.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (!orderRepository.existsByShareToken(token)) {
                return token;
            }
        }
        throw new BusinessException("Unable to create a secure share link");
    }

    private String publicOrderLink(String shareToken) {
        return frontendBaseUrl.replaceAll("/+$", "") + "/orders/token/" + shareToken;
    }

    public String buyerPaymentLink(Order order) {
        if (order == null
                || order.getCreatorRole() != CreatorRole.VENDOR
                || order.getFundingMode() != OrderFundingMode.PAYSTACK_CHECKOUT
                || order.getTotalAmount() == null
                || order.getStatus() == OrderStatus.CANCELLED) {
            return null;
        }
        return publicOrderLink(order.getShareToken());
    }

    private void requireBuyerPaymentOrder(Order order) {
        if (order.getCreatorRole() != CreatorRole.VENDOR
                || order.getFundingMode() != OrderFundingMode.PAYSTACK_CHECKOUT
                || order.getTotalAmount() == null) {
            throw new ResourceNotFoundException("Buyer payment order not found");
        }
    }

    private void requireActiveSelectedQuote(Order order) {
        ShippingQuote quote = quoteRepository.findByShipment_Order_IdAndStatus(
                        order.getId(),
                        ShippingQuoteStatus.SELECTED
                )
                .orElseThrow(() -> new BusinessException("Selected shipping quote is not available"));
        if (!quote.getExpiresAt().isAfter(Instant.now())) {
            throw new BusinessException("Selected shipping quote has expired; creator must request fresh rates");
        }
    }

    private String lastFour(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            throw new BusinessException("Resolved vendor account number is invalid");
        }
        return accountNumber.substring(accountNumber.length() - 4);
    }

    private String accessRecoverySubject(Order order) {
        return "access:" + order.getShareToken();
    }

    private String maskPhone(String phone) {
        if (phone.length() <= 4) {
            return "****";
        }
        return phone.substring(0, Math.min(5, phone.length() - 2))
                + "*".repeat(Math.max(4, phone.length() - 7))
                + phone.substring(phone.length() - 2);
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
