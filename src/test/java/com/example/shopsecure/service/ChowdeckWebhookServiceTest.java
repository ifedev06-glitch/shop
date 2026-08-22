package com.example.shopsecure.service;

import com.example.shopsecure.exception.ServiceUnavailableException;
import com.example.shopsecure.provider.shipping.ShippingProviderCode;
import com.example.shopsecure.repository.OrderShipmentRepository;
import com.example.shopsecure.repository.WaybillShipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChowdeckWebhookServiceTest {

    private OrderShipmentRepository orderShipmentRepository;
    private WaybillShipmentRepository waybillShipmentRepository;
    private OrderShippingService orderShippingService;
    private WaybillShippingService waybillShippingService;
    private ChowdeckWebhookService webhookService;

    @BeforeEach
    void setUp() {
        orderShipmentRepository = mock(OrderShipmentRepository.class);
        waybillShipmentRepository = mock(WaybillShipmentRepository.class);
        orderShippingService = mock(OrderShippingService.class);
        waybillShippingService = mock(WaybillShippingService.class);
        webhookService = new ChowdeckWebhookService(
                orderShipmentRepository,
                waybillShipmentRepository,
                orderShippingService,
                waybillShippingService
        );
    }

    @Test
    void knownOrderReferenceTriggersCanonicalOrderReconciliation() {
        String reference = "order-42";
        when(orderShipmentRepository.findOrderIdByProviderAndProviderShipmentId(
                ShippingProviderCode.CHOWDECK,
                reference
        )).thenReturn(Optional.of(42L));
        when(waybillShipmentRepository.findWaybillIdByProviderAndProviderShipmentId(
                ShippingProviderCode.CHOWDECK,
                reference
        )).thenReturn(Optional.empty());

        webhookService.handle(reference);

        verify(orderShippingService).reconcileChowdeckTracking(42L);
        verify(waybillShippingService, never()).reconcileChowdeckTracking(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void knownWaybillReferenceTriggersCanonicalWaybillReconciliation() {
        String reference = "waybill-0d07af43-8db8-44a8-bbe0-caca6bc0374d";
        when(orderShipmentRepository.findOrderIdByProviderAndProviderShipmentId(
                ShippingProviderCode.CHOWDECK,
                reference
        )).thenReturn(Optional.empty());
        when(waybillShipmentRepository.findWaybillIdByProviderAndProviderShipmentId(
                ShippingProviderCode.CHOWDECK,
                reference
        )).thenReturn(Optional.of(17L));

        webhookService.handle(reference);

        verify(waybillShippingService).reconcileChowdeckTracking(17L);
        verify(orderShippingService, never()).reconcileChowdeckTracking(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void unknownReferenceIsAcknowledgedWithoutCallingChowdeck() {
        String reference = "order-404";
        when(orderShipmentRepository.findOrderIdByProviderAndProviderShipmentId(
                ShippingProviderCode.CHOWDECK,
                reference
        )).thenReturn(Optional.empty());
        when(waybillShipmentRepository.findWaybillIdByProviderAndProviderShipmentId(
                ShippingProviderCode.CHOWDECK,
                reference
        )).thenReturn(Optional.empty());

        webhookService.handle(reference);

        verify(orderShippingService, never()).reconcileChowdeckTracking(org.mockito.ArgumentMatchers.anyLong());
        verify(waybillShippingService, never()).reconcileChowdeckTracking(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void crossDomainReferenceCollisionFailsClosed() {
        String reference = "order-42";
        when(orderShipmentRepository.findOrderIdByProviderAndProviderShipmentId(
                ShippingProviderCode.CHOWDECK,
                reference
        )).thenReturn(Optional.of(42L));
        when(waybillShipmentRepository.findWaybillIdByProviderAndProviderShipmentId(
                ShippingProviderCode.CHOWDECK,
                reference
        )).thenReturn(Optional.of(17L));

        webhookService.handle(reference);

        verify(orderShippingService, never()).reconcileChowdeckTracking(org.mockito.ArgumentMatchers.anyLong());
        verify(waybillShippingService, never()).reconcileChowdeckTracking(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void providerFailureReturnsGenericTemporaryFailure() {
        String reference = "order-42";
        when(orderShipmentRepository.findOrderIdByProviderAndProviderShipmentId(
                ShippingProviderCode.CHOWDECK,
                reference
        )).thenReturn(Optional.of(42L));
        when(waybillShipmentRepository.findWaybillIdByProviderAndProviderShipmentId(
                ShippingProviderCode.CHOWDECK,
                reference
        )).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("sensitive provider failure for " + reference))
                .when(orderShippingService).reconcileChowdeckTracking(42L);

        assertThatThrownBy(() -> webhookService.handle(reference))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Chowdeck webhook reconciliation is temporarily unavailable")
                .hasMessageNotContaining(reference)
                .hasMessageNotContaining("sensitive provider failure");
    }

    @Test
    void duplicateCallbacksRecheckCanonicalStateWithoutCrossRouting() {
        String reference = "order-42";
        when(orderShipmentRepository.findOrderIdByProviderAndProviderShipmentId(
                ShippingProviderCode.CHOWDECK,
                reference
        )).thenReturn(Optional.of(42L));
        when(waybillShipmentRepository.findWaybillIdByProviderAndProviderShipmentId(
                ShippingProviderCode.CHOWDECK,
                reference
        )).thenReturn(Optional.empty());

        webhookService.handle(reference);
        webhookService.handle(reference);

        verify(orderShippingService, times(2)).reconcileChowdeckTracking(42L);
        verify(waybillShippingService, never()).reconcileChowdeckTracking(org.mockito.ArgumentMatchers.anyLong());
    }
}
