package com.example.shopsecure.service;

import com.example.shopsecure.entity.OrderTrackingAccessToken;
import com.example.shopsecure.entity.OrderTrackingNotificationStatus;
import com.example.shopsecure.event.OrderFundedEvent;
import com.example.shopsecure.repository.OrderTrackingNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderTrackingListener {

    private static final int MAX_BATCH_SIZE = 100;

    private final OrderTrackingService trackingService;
    private final OrderTrackingNotificationService notificationService;
    private final OrderTrackingNotificationRepository notificationRepository;

    @Value("${order.tracking-notification.batch-size:25}")
    private int batchSize;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void activateTracking(OrderFundedEvent event) {
        OrderTrackingAccessToken access = trackingService.activate(event.orderId());
        notificationService.enqueue(access.getOrder());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void dispatchTrackingEmails(OrderFundedEvent event) {
        notificationRepository.findIdsByOrderAndStatusDue(
                event.orderId(),
                OrderTrackingNotificationStatus.PENDING,
                Instant.now()
        ).forEach(this::dispatch);
    }

    @Scheduled(
            initialDelayString = "${order.tracking-notification.initial-delay-ms:60000}",
            fixedDelayString = "${order.tracking-notification.delay-ms:60000}"
    )
    public void retryPendingEmails() {
        notificationRepository.findIdsDue(
                OrderTrackingNotificationStatus.PENDING,
                Instant.now(),
                PageRequest.of(0, Math.min(MAX_BATCH_SIZE, Math.max(1, batchSize)))
        ).forEach(this::dispatch);
    }

    private void dispatch(Long notificationId) {
        try {
            notificationService.dispatch(notificationId);
        } catch (RuntimeException exception) {
            log.error("Unable to dispatch order tracking notification: notificationId={}",
                    notificationId, exception);
        }
    }
}
