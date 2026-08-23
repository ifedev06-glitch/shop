package com.example.shopsecure.service;

import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.OrderCounterparty;
import com.example.shopsecure.entity.OrderDispute;
import com.example.shopsecure.entity.OrderDisputeStatus;
import com.example.shopsecure.entity.PaymentStatus;
import com.example.shopsecure.event.OrderDisputeOpenedEvent;
import com.example.shopsecure.repository.OrderCounterpartyRepository;
import com.example.shopsecure.repository.OrderDisputeRepository;
import com.example.shopsecure.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class OrderDisputeService {

    private final OrderRepository orderRepository;
    private final OrderCounterpartyRepository counterpartyRepository;
    private final OrderDisputeRepository disputeRepository;
    private final OrderDisputeNotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void submit(Long orderId, String reason) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null
                || order.getPaymentStatus() != PaymentStatus.CONFIRMED
                || order.getFundedAt() == null
                || disputeRepository.existsByOrder_Id(orderId)) {
            return;
        }

        OrderCounterparty counterparty = counterpartyRepository.findByOrder_Id(orderId).orElse(null);
        if (counterparty == null) {
            return;
        }

        Instant now = Instant.now();
        OrderDispute dispute = disputeRepository.save(OrderDispute.builder()
                .order(order)
                .reason(reason.strip())
                .status(OrderDisputeStatus.OPEN)
                .createdAt(now)
                .updatedAt(now)
                .build());
        notificationService.enqueueOpened(dispute, counterparty);
        eventPublisher.publishEvent(new OrderDisputeOpenedEvent(dispute.getId()));
    }
}
