package com.example.shopsecure.service;

import com.example.shopsecure.event.WaybillRefundQueuedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class WaybillRefundDispatchListener {

    private final WaybillRefundService refundService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(WaybillRefundQueuedEvent event) {
        try {
            refundService.dispatch(event.refundId());
        } catch (RuntimeException exception) {
            log.warn("Unable to dispatch queued waybill refund: refundId={}", event.refundId());
        }
    }
}
