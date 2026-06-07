package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.CheckoutCustomerRequest;
import com.example.shopsecure.dto.request.CheckoutLinkProductRequest;
import com.example.shopsecure.dto.request.CreateCheckoutLinkRequest;
import com.example.shopsecure.dto.response.*;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
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

    private static final BigDecimal DELIVERY_FEE = new BigDecimal("100.00");

    @Value("${app.base-url}")
    private String baseUrl;

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

            BigDecimal totalWithDelivery = request.getAmount().add(DELIVERY_FEE);

            CheckoutLink checkoutLink = CheckoutLink.builder()
                    .slug(slug)
                    .business(business)
                    .title(request.getTitle())
                    .description(request.getDescription())
                    .customLink(true)
                    .totalAmount(totalWithDelivery)
                    .active(true)
                    .createdAt(LocalDateTime.now())
                    .build();

            checkoutLinkRepository.save(checkoutLink);

            return CreateCheckoutLinkResponse.builder()
                    .slug(slug)
                    .checkoutUrl(baseUrl + "/checkout/" + slug)
                    .deliveryFee(DELIVERY_FEE)
                    .totalAmount(totalWithDelivery)
                    .build();
        }

        /*
         * PRODUCT CHECKOUT LINK
         */
        BigDecimal totalAmount = BigDecimal.ZERO;

        CheckoutLink checkoutLink = CheckoutLink.builder()
                .slug(slug)
                .business(business)
                .customLink(false)
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();

        CheckoutLink savedLink =
                checkoutLinkRepository.save(checkoutLink);

        for (CheckoutLinkProductRequest item : request.getItems()) {

            Product product =
                    productRepository.findByIdAndBusiness_Id(
                                    item.getProductId(),
                                    business.getId()
                            )
                            .orElseThrow(() ->
                                    new RuntimeException("Product not found"));

            BigDecimal subtotal =
                    product.getPrice()
                            .multiply(BigDecimal.valueOf(item.getQuantity()));

            totalAmount = totalAmount.add(subtotal);

            CheckoutLinkItem linkItem =
                    CheckoutLinkItem.builder()
                            .checkoutLink(savedLink)
                            .product(product)
                            .quantity(item.getQuantity())
                            .price(product.getPrice())
                            .subtotal(subtotal)
                            .build();

            checkoutLinkItemRepository.save(linkItem);
        }

        BigDecimal totalWithDelivery = totalAmount.add(DELIVERY_FEE);

        savedLink.setTotalAmount(totalWithDelivery);
        checkoutLinkRepository.save(savedLink);

        return CreateCheckoutLinkResponse.builder()
                .slug(slug)
                .checkoutUrl(baseUrl + "/checkout/" + slug)
                .deliveryFee(DELIVERY_FEE)
                .totalAmount(totalWithDelivery)
                .build();
    }

    public CheckoutLinkDetailsResponse getCheckoutLink(
            String slug
    ) {

        CheckoutLink checkoutLink =
                checkoutLinkRepository.findBySlug(slug)
                        .orElseThrow(() ->
                                new RuntimeException("Checkout link not found"));

        if (Boolean.TRUE.equals(checkoutLink.getCustomLink())) {

            return CheckoutLinkDetailsResponse.builder()
                    .businessName(
                            checkoutLink.getBusiness().getBusinessName()
                    )
                    .slug(checkoutLink.getSlug())
                    .title(checkoutLink.getTitle())
                    .description(checkoutLink.getDescription())
                    .customLink(true)
                    .deliveryFee(DELIVERY_FEE)
                    .subtotalAmount(checkoutLink.getTotalAmount().subtract(DELIVERY_FEE))
                    .totalAmount(checkoutLink.getTotalAmount())
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
                .businessName(
                        checkoutLink.getBusiness().getBusinessName()
                )
                .slug(checkoutLink.getSlug())
                .customLink(false)
                .deliveryFee(DELIVERY_FEE)
                .subtotalAmount(checkoutLink.getTotalAmount().subtract(DELIVERY_FEE))
                .totalAmount(checkoutLink.getTotalAmount())
                .items(items)
                .build();
    }

    public CheckoutPaymentResponse payForCheckoutLink(
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

        String orderNumber =
                "ORD-" +
                        UUID.randomUUID()
                                .toString()
                                .substring(0, 8)
                                .toUpperCase();

        String reference =
                "PAY-" +
                        UUID.randomUUID()
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
                .totalAmount(checkoutLink.getTotalAmount())
                .title(checkoutLink.getTitle())
                .description(checkoutLink.getDescription())
                .status(OrderStatus.PENDING)
                .paymentStatus("PENDING")
                .createdAt(LocalDateTime.now())
                .build();

        Order savedOrder = orderRepository.save(order);

        /*
         * Only create OrderItems for product-based checkout links.
         * Custom payment links have no products attached.
         */
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

        /*
         * Save payment record before redirecting to Paystack.
         * Webhook will use the reference to locate this payment.
         */
        Payment payment = Payment.builder()
                .reference(reference)
                .order(savedOrder)
                .amount(checkoutLink.getTotalAmount())
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        paymentRepository.save(payment);

        Map<String, Object> paystackResponse =
                paystackService.initializeTransaction(
                        request.getEmail(),
                        checkoutLink.getTotalAmount(),
                        reference
                );

        return CheckoutPaymentResponse.builder()
                .orderNumber(savedOrder.getOrderNumber())
                .reference(reference)
                .authorizationUrl(
                        paystackResponse.get("authorization_url").toString()
                )
                .build();
    }

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