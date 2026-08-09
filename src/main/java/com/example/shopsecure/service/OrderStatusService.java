package com.example.shopsecure.service;

import com.example.shopsecure.entity.Order;
import com.example.shopsecure.entity.OrderStatus;
import com.example.shopsecure.entity.OrderStatusHistory;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.repository.OrderStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OrderStatusService {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = transitions();

    private final OrderStatusHistoryRepository historyRepository;

    public void recordInitial(Order order, String reason) {
        historyRepository.save(history(order, null, order.getStatus(), reason));
    }

    public boolean transition(Order order, OrderStatus target, String reason) {
        OrderStatus current = order.getStatus();
        if (current == target) {
            return false;
        }
        if (!ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(target)) {
            throw new BusinessException("Order cannot move from " + current + " to " + target);
        }
        order.setStatus(target);
        order.setUpdatedAt(Instant.now());
        historyRepository.save(history(order, current, target, reason));
        return true;
    }

    private OrderStatusHistory history(Order order, OrderStatus from, OrderStatus to, String reason) {
        return OrderStatusHistory.builder()
                .order(order)
                .fromStatus(from)
                .toStatus(to)
                .reason(reason)
                .createdAt(Instant.now())
                .build();
    }

    private static Map<OrderStatus, Set<OrderStatus>> transitions() {
        Map<OrderStatus, Set<OrderStatus>> transitions = new EnumMap<>(OrderStatus.class);
        transitions.put(OrderStatus.AWAITING_COUNTERPARTY, EnumSet.of(
                OrderStatus.AWAITING_SHIPPING_RATE,
                OrderStatus.CANCELLED
        ));
        transitions.put(OrderStatus.AWAITING_SHIPPING_RATE, EnumSet.of(
                OrderStatus.AWAITING_PAYMENT,
                OrderStatus.CANCELLED
        ));
        transitions.put(OrderStatus.AWAITING_PAYMENT, EnumSet.of(
                OrderStatus.SHIPMENT_BOOKING,
                OrderStatus.PAYMENT_FAILED,
                OrderStatus.CANCELLED
        ));
        transitions.put(OrderStatus.PAYMENT_FAILED, EnumSet.of(
                OrderStatus.AWAITING_PAYMENT,
                OrderStatus.CANCELLED
        ));
        transitions.put(OrderStatus.SHIPMENT_BOOKING, EnumSet.of(
                OrderStatus.READY_FOR_DELIVERY,
                OrderStatus.SHIPPING_FAILED,
                OrderStatus.MANUAL_REVIEW
        ));
        transitions.put(OrderStatus.SHIPPING_FAILED, EnumSet.of(
                OrderStatus.SHIPMENT_BOOKING,
                OrderStatus.CANCELLED,
                OrderStatus.MANUAL_REVIEW
        ));
        transitions.put(OrderStatus.READY_FOR_DELIVERY, EnumSet.of(
                OrderStatus.PAYOUT_PENDING,
                OrderStatus.COMPLETED,
                OrderStatus.SHIPPING_FAILED
        ));
        transitions.put(OrderStatus.PAYOUT_PENDING, EnumSet.of(
                OrderStatus.COMPLETED,
                OrderStatus.PAYOUT_FAILED
        ));
        transitions.put(OrderStatus.PAYOUT_FAILED, EnumSet.of(
                OrderStatus.PAYOUT_PENDING,
                OrderStatus.COMPLETED
        ));
        transitions.put(OrderStatus.COMPLETED, EnumSet.of(OrderStatus.PAYOUT_FAILED));
        return Map.copyOf(transitions);
    }
}
