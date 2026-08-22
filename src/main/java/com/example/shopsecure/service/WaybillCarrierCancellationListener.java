package com.example.shopsecure.service;

import com.example.shopsecure.event.WaybillCarrierCancellationConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class WaybillCarrierCancellationListener {

    private final WaybillCancellationService cancellationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void finalizeCancellation(WaybillCarrierCancellationConfirmedEvent event) {
        try {
            cancellationService.finalizeCarrierCancellation(event.waybillId());
        } catch (RuntimeException exception) {
            log.warn(
                    "Unable to finalize carrier-confirmed waybill cancellation: waybillId={}",
                    event.waybillId()
            );
        }
    }
}
