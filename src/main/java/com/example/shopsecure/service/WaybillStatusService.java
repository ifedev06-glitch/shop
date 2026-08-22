package com.example.shopsecure.service;

import com.example.shopsecure.entity.Waybill;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.entity.WaybillStatusHistory;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.repository.WaybillStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WaybillStatusService {

    private static final Map<WaybillStatus, Set<WaybillStatus>> ALLOWED_TRANSITIONS = transitions();

    private final WaybillStatusHistoryRepository historyRepository;

    public void recordInitial(Waybill waybill, String reason) {
        requireReason(reason);
        historyRepository.save(history(waybill, null, waybill.getStatus(), reason.trim()));
    }

    public boolean transition(Waybill waybill, WaybillStatus target, String reason) {
        requireReason(reason);
        WaybillStatus current = waybill.getStatus();
        if (current == target) {
            return false;
        }
        if (!ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(target)) {
            throw new BusinessException("Waybill cannot move from " + current + " to " + target);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        waybill.setStatus(target);
        waybill.setUpdatedAt(now);
        historyRepository.save(WaybillStatusHistory.builder()
                .waybill(waybill)
                .fromStatus(current)
                .toStatus(target)
                .reason(reason.trim())
                .createdAt(now)
                .build());
        return true;
    }

    private WaybillStatusHistory history(
            Waybill waybill,
            WaybillStatus from,
            WaybillStatus to,
            String reason
    ) {
        return WaybillStatusHistory.builder()
                .waybill(waybill)
                .fromStatus(from)
                .toStatus(to)
                .reason(reason)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("Waybill status change reason is required");
        }
        if (reason.trim().length() > 500) {
            throw new BusinessException("Waybill status change reason must not exceed 500 characters");
        }
    }

    private static Map<WaybillStatus, Set<WaybillStatus>> transitions() {
        Map<WaybillStatus, Set<WaybillStatus>> transitions = new EnumMap<>(WaybillStatus.class);
        transitions.put(WaybillStatus.AWAITING_PARTY_DETAILS, EnumSet.of(
                WaybillStatus.AWAITING_QUOTE,
                WaybillStatus.CANCELLATION_PENDING,
                WaybillStatus.CANCELLED,
                WaybillStatus.MANUAL_REVIEW
        ));
        transitions.put(WaybillStatus.AWAITING_QUOTE, EnumSet.of(
                WaybillStatus.AWAITING_PAYMENT,
                WaybillStatus.PAYMENT_REVIEW,
                WaybillStatus.CANCELLATION_PENDING,
                WaybillStatus.CANCELLED,
                WaybillStatus.MANUAL_REVIEW
        ));
        transitions.put(WaybillStatus.AWAITING_PAYMENT, EnumSet.of(
                WaybillStatus.AWAITING_QUOTE,
                WaybillStatus.PAYMENT_REVIEW,
                WaybillStatus.SHIPMENT_BOOKING,
                WaybillStatus.CANCELLATION_PENDING,
                WaybillStatus.CANCELLED,
                WaybillStatus.MANUAL_REVIEW
        ));
        transitions.put(WaybillStatus.PAYMENT_REVIEW, EnumSet.of(
                WaybillStatus.AWAITING_PAYMENT,
                WaybillStatus.SHIPMENT_BOOKING,
                WaybillStatus.CANCELLATION_PENDING,
                WaybillStatus.REFUND_PENDING,
                WaybillStatus.MANUAL_REVIEW
        ));
        transitions.put(WaybillStatus.SHIPMENT_BOOKING, EnumSet.of(
                WaybillStatus.READY_FOR_DELIVERY,
                WaybillStatus.SHIPPING_FAILED,
                WaybillStatus.CANCELLATION_PENDING,
                WaybillStatus.MANUAL_REVIEW
        ));
        transitions.put(WaybillStatus.READY_FOR_DELIVERY, EnumSet.of(
                WaybillStatus.COMPLETED,
                WaybillStatus.SHIPPING_FAILED,
                WaybillStatus.CANCELLATION_PENDING,
                WaybillStatus.MANUAL_REVIEW
        ));
        transitions.put(WaybillStatus.SHIPPING_FAILED, EnumSet.of(
                WaybillStatus.SHIPMENT_BOOKING,
                WaybillStatus.CANCELLATION_PENDING,
                WaybillStatus.CANCELLED,
                WaybillStatus.MANUAL_REVIEW
        ));
        transitions.put(WaybillStatus.CANCELLATION_PENDING, EnumSet.of(
                WaybillStatus.CANCELLED,
                WaybillStatus.REFUND_PENDING,
                WaybillStatus.MANUAL_REVIEW
        ));
        transitions.put(WaybillStatus.REFUND_PENDING, EnumSet.of(
                WaybillStatus.REFUNDED,
                WaybillStatus.MANUAL_REVIEW
        ));
        transitions.put(WaybillStatus.MANUAL_REVIEW, EnumSet.of(
                WaybillStatus.AWAITING_PAYMENT,
                WaybillStatus.SHIPMENT_BOOKING,
                WaybillStatus.READY_FOR_DELIVERY,
                WaybillStatus.CANCELLATION_PENDING,
                WaybillStatus.REFUND_PENDING,
                WaybillStatus.COMPLETED,
                WaybillStatus.CANCELLED
        ));
        transitions.put(WaybillStatus.CANCELLED, EnumSet.of(
                WaybillStatus.REFUND_PENDING,
                WaybillStatus.MANUAL_REVIEW
        ));
        transitions.put(WaybillStatus.COMPLETED, EnumSet.of(WaybillStatus.MANUAL_REVIEW));
        transitions.put(WaybillStatus.REFUNDED, EnumSet.of(WaybillStatus.MANUAL_REVIEW));
        return Map.copyOf(transitions);
    }
}
