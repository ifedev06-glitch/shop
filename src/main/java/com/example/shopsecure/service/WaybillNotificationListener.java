package com.example.shopsecure.service;

import com.example.shopsecure.entity.WaybillEmailNotificationStatus;
import com.example.shopsecure.event.WaybillPickupArrangedEvent;
import com.example.shopsecure.repository.WaybillEmailNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
@RequiredArgsConstructor
@Slf4j
public class WaybillNotificationListener {

    private static final int MAX_BATCH_SIZE = 100;

    private final WaybillNotificationService notificationService;
    private final WaybillEmailNotificationRepository notificationRepository;

    @Value("${waybill.notification.batch-size:25}")
    private int batchSize;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void dispatchAwaitingPickup(WaybillPickupArrangedEvent event) {
        LocalDateTime now = utcNow();
        notificationRepository.findIdsByWaybillAndStatusDue(
                event.waybillId(),
                WaybillEmailNotificationStatus.PENDING,
                now
        ).forEach(this::dispatch);
    }

    @Scheduled(
            initialDelayString = "${waybill.notification.initial-delay-ms:60000}",
            fixedDelayString = "${waybill.notification.delay-ms:60000}"
    )
    public void retryPendingEmails() {
        notificationRepository.findIdsDue(
                WaybillEmailNotificationStatus.PENDING,
                utcNow(),
                PageRequest.of(0, Math.min(MAX_BATCH_SIZE, Math.max(1, batchSize)))
        ).forEach(this::dispatch);
    }

    private void dispatch(Long notificationId) {
        try {
            notificationService.dispatch(notificationId);
        } catch (RuntimeException exception) {
            log.error("Unable to dispatch waybill email notification: notificationId={}",
                    notificationId, exception);
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
