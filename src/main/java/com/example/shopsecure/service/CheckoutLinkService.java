package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.CheckoutCustomerRequest;
import com.example.shopsecure.dto.request.CheckoutLinkProductRequest;
import com.example.shopsecure.dto.request.CreateCheckoutLinkRequest;
import com.example.shopsecure.dto.response.*;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutLinkService {

    private final CheckoutLinkRepository checkoutLinkRepository;
    private final CheckoutLinkItemRepository checkoutLinkItemRepository;
    private final ProductRepository productRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final PaystackService paystackService;
    private final ShippingService shippingService;

    @Value("${app.base-url}")
    private String baseUrl;

    // ---------------------------
    // CREATE CHECKOUT LINK
    // ---------------------------
    @Transactional
    public CreateCheckoutLinkResponse createCheckoutLink(
            CreateCheckoutLinkRequest request
    ) {
        Business business = getCurrentBusiness();
        String slug = generateSlug();

        /*
         * CUSTOM PAYMENT LINK
         */
        if (request.getItems() == null || request.getItems().isEmpty()) {

            if (request.getAmount() == null) {
                throw new RuntimeException(
                        "Amount is required for custom payment links"
                );
            }

            CheckoutLink checkoutLink = CheckoutLink.builder()
                    .slug(slug)
                    .business(business)
                    .title(request.getTitle())
                    .description(request.getDescription())
                    .customLink(true)
                    .subtotalAmount(request.getAmount())
                    .active(true)
                    .createdAt(LocalDateTime.now())
                    .build();

            checkoutLinkRepository.save(checkoutLink);
            log.info("Checkout link created: slug={}", slug);

            return CreateCheckoutLinkResponse.builder()
                    .slug(slug)
                    .checkoutUrl(baseUrl + "/checkout/" + slug)
                    .subtotalAmount(request.getAmount())
                    .build();
        }

        /*
         * PRODUCT CHECKOUT LINK
         */
        BigDecimal subtotal = BigDecimal.ZERO;

        CheckoutLink checkoutLink = CheckoutLink.builder()
                .slug(slug)
                .business(business)
                .customLink(false)
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();

        CheckoutLink savedLink = checkoutLinkRepository.save(checkoutLink);

        for (CheckoutLinkProductRequest item : request.getItems()) {

            Product product =
                    productRepository.findByIdAndBusiness_Id(
                                    item.getProductId(),
                                    business.getId()
                            )
                            .orElseThrow(() ->
                                    new RuntimeException("Product not found"));

            BigDecimal itemSubtotal =
                    product.getPrice()
                            .multiply(BigDecimal.valueOf(item.getQuantity()));

            subtotal = subtotal.add(itemSubtotal);

            CheckoutLinkItem linkItem =
                    CheckoutLinkItem.builder()
                            .checkoutLink(savedLink)
                            .product(product)
                            .quantity(item.getQuantity())
                            .price(product.getPrice())
                            .subtotal(itemSubtotal)
                            .build();

            checkoutLinkItemRepository.save(linkItem);
        }

        savedLink.setSubtotalAmount(subtotal);
        checkoutLinkRepository.save(savedLink);
        log.info("Checkout link created: slug={}", slug);

        return CreateCheckoutLinkResponse.builder()
                .slug(slug)
                .checkoutUrl(baseUrl + "/checkout/" + slug)
                .subtotalAmount(subtotal)
                .build();
    }

    // ---------------------------
    // GET CHECKOUT LINK DETAILS
    // ---------------------------
    public CheckoutLinkDetailsResponse getCheckoutLink(String slug) {

        CheckoutLink checkoutLink =
                checkoutLinkRepository.findBySlug(slug)
                        .orElseThrow(() ->
                                new RuntimeException("Checkout link not found"));

        if (Boolean.TRUE.equals(checkoutLink.getCustomLink())) {

            return CheckoutLinkDetailsResponse.builder()
                    .businessName(checkoutLink.getBusiness().getBusinessName())
                    .slug(checkoutLink.getSlug())
                    .title(checkoutLink.getTitle())
                    .description(checkoutLink.getDescription())
                    .customLink(true)
                    .subtotalAmount(checkoutLink.getSubtotalAmount())
                    .items(List.of())
                    .build();
        }

        List<CheckoutLinkItemResponse> items =
                checkoutLinkItemRepository
                        .findByCheckoutLink_Id(checkoutLink.getId())
                        .stream()
                        .map(item ->
                                CheckoutLinkItemResponse.builder()
                                        .productId(item.getProduct().getId())
                                        .productName(item.getProduct().getName())
                                        .quantity(item.getQuantity())
                                        .price(item.getPrice())
                                        .subtotal(item.getSubtotal())
                                        .imageUrls(item.getProduct().getImageUrls())
                                        .build()
                        )
                        .toList();

        return CheckoutLinkDetailsResponse.builder()
                .businessName(checkoutLink.getBusiness().getBusinessName())
                .slug(checkoutLink.getSlug())
                .customLink(false)
                .subtotalAmount(checkoutLink.getSubtotalAmount())
                .items(items)
                .build();
    }

    // ---------------------------
    // PAY FOR CHECKOUT LINK
    // ---------------------------
    public CheckoutPaymentResponse payForCheckoutLink(
            String slug,
            CheckoutCustomerRequest request
    ) {
        CheckoutPaymentData data = createOrderForCheckoutLink(slug, request);

        Map<String, Object> paystackResponse =
                paystackService.initializeTransaction(
                        request.getEmail(),
                        data.totalAmount(),
                        data.reference()
                );

        log.info("Payment via checkout link: slug={}, orderNumber={}, reference={}", slug, data.orderNumber(), data.reference());

        return CheckoutPaymentResponse.builder()
                .orderNumber(data.orderNumber())
                .reference(data.reference())
                .authorizationUrl(
                        paystackResponse.get("authorization_url").toString()
                )
                .build();
    }

    @Transactional
    protected CheckoutPaymentData createOrderForCheckoutLink(
            String slug,
            CheckoutCustomerRequest request
    ) {
        CheckoutLink checkoutLink =
                checkoutLinkRepository.findBySlug(slug)
                        .orElseThrow(() ->
                                new RuntimeException("Checkout link not found"));

        if (!checkoutLink.getActive()) {
            throw new RuntimeException("Checkout link is inactive");
        }

        ShippingRate rate = shippingService.getShippingRate(request.getShippingArea());
        BigDecimal shippingFee = rate.getFee();
        BigDecimal totalAmount = checkoutLink.getSubtotalAmount().add(shippingFee);

        // validate stock for product-based checkout links
        if (!Boolean.TRUE.equals(checkoutLink.getCustomLink())) {
            log.info("Stock validation started for checkout link: slug={}", slug);
            List<CheckoutLinkItem> linkItems =
                    checkoutLinkItemRepository
                            .findByCheckoutLink_Id(checkoutLink.getId());
            for (CheckoutLinkItem item : linkItems) {
                Product product = productRepository.findByIdForUpdate(item.getProduct().getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
                if (product.getStock() < item.getQuantity()) {
                    log.warn("Stock validation failed: product='{}', requested={}, available={}",
                            product.getName(), item.getQuantity(), product.getStock());
                    throw new com.example.shopsecure.exception.BusinessException(
                            "Product '" + product.getName() + "' only has " + product.getStock() + " items available."
                    );
                }
            }
            log.info("Stock validation successful for checkout link: slug={}", slug);
        }

        String orderNumber =
                "ORD-" + UUID.randomUUID()
                        .toString()
                        .substring(0, 8)
                        .toUpperCase();

        String reference =
                "PAY-" + UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        .substring(0, 12)
                        .toUpperCase();

        Order order = Order.builder()
                .orderNumber(orderNumber)
                .paymentReference(reference)
                .business(checkoutLink.getBusiness())
                .customerName(request.getCustomerName())
                .email(request.getEmail())
                .phoneNumber(request.getPhoneNumber())
                .deliveryAddress(request.getDeliveryAddress())
                .shippingArea(request.getShippingArea())
                .shippingFee(shippingFee)
                .totalAmount(totalAmount)
                .title(checkoutLink.getTitle())
                .description(checkoutLink.getDescription())
                .status(OrderStatus.PENDING)
                .paymentStatus("PENDING")
                .createdAt(LocalDateTime.now())
                .build();

        Order savedOrder = orderRepository.save(order);

        if (!Boolean.TRUE.equals(checkoutLink.getCustomLink())) {
            List<CheckoutLinkItem> linkItems =
                    checkoutLinkItemRepository
                            .findByCheckoutLink_Id(checkoutLink.getId());

            for (CheckoutLinkItem item : linkItems) {
                OrderItem orderItem =
                        OrderItem.builder()
                                .order(savedOrder)
                                .product(item.getProduct())
                                .quantity(item.getQuantity())
                                .price(item.getPrice())
                                .subtotal(item.getSubtotal())
                                .build();

                orderItemRepository.save(orderItem);
            }
        }

        Payment payment = Payment.builder()
                .reference(reference)
                .order(savedOrder)
                .amount(totalAmount)
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        paymentRepository.save(payment);

        return new CheckoutPaymentData(savedOrder.getOrderNumber(), reference, totalAmount);
    }

    private record CheckoutPaymentData(String orderNumber, String reference, BigDecimal totalAmount) {}

    private Business getCurrentBusiness() {

        String email =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication()
                        .getName();

        User user =
                userRepository.findByEmail(email)
                        .orElseThrow(() ->
                                new RuntimeException("User not found"));

        return businessRepository
                .findByOwner_Id(user.getId())
                .orElseThrow(() ->
                        new RuntimeException("Business not found"));
    }

    private String generateSlug() {
        return UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12);
    }
}