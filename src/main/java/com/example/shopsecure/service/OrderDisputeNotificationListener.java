package com.example.shopsecure.service;

import com.example.shopsecure.entity.OrderDisputeNotificationStatus;
import com.example.shopsecure.event.OrderDisputeOpenedEvent;
import com.example.shopsecure.repository.OrderDisputeNotificationRepository;
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
public class OrderDisputeNotificationListener {

    private static final int MAX_BATCH_SIZE = 100;

    private final OrderDisputeNotificationService notificationService;
    private final OrderDisputeNotificationRepository notificationRepository;

    @Value("${order.dispute-notification.batch-size:25}")
    private int batchSize;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void dispatchOpenedEmails(OrderDisputeOpenedEvent event) {
        notificationRepository.findIdsByDisputeAndStatusDue(
                event.disputeId(),
                OrderDisputeNotificationStatus.PENDING,
                Instant.now()
        ).forEach(this::dispatch);
    }

    @Scheduled(
            initialDelayString = "${order.dispute-notification.initial-delay-ms:60000}",
            fixedDelayString = "${order.dispute-notification.delay-ms:60000}"
    )
    public void retryPendingEmails() {
        notificationRepository.findIdsDue(
                OrderDisputeNotificationStatus.PENDING,
                Instant.now(),
                PageRequest.of(0, Math.min(MAX_BATCH_SIZE, Math.max(1, batchSize)))
        ).forEach(this::dispatch);
    }

    private void dispatch(Long notificationId) {
        try {
            notificationService.dispatch(notificationId);
        } catch (RuntimeException exception) {
            log.error("Unable to dispatch order dispute notification: notificationId={}",
                    notificationId, exception);
        }
    }
}
