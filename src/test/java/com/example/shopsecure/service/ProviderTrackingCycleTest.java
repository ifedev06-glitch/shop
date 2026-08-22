package com.example.shopsecure.service;

import com.example.shopsecure.repository.OrderShipmentRepository;
import com.example.shopsecure.repository.WaybillShipmentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ProviderTrackingCycleTest {

    @Test
    void orderTrackingReachesFrozenHighWaterBeforeIncludingNewerOrders() {
        OrderShippingService shippingService = mock(OrderShippingService.class);
        OrderShipmentRepository shipmentRepository = mock(OrderShipmentRepository.class);
        OrderFundedListener listener = new OrderFundedListener(shippingService, shipmentRepository);
        ReflectionTestUtils.setField(listener, "reconciliationBatchSize", 1);
        when(shipmentRepository.findOrderIdsReadyForBookingReconciliation(any(), any(), any()))
                .thenReturn(List.of());
        when(shipmentRepository.findProviderTrackingHighWaterOrderId(any(), any(), any()))
                .thenReturn(100L, 200L);
        when(shipmentRepository.findOrderIdsForProviderTracking(
                any(), any(), any(), anyLong(), anyLong(), any(Pageable.class)
        )).thenReturn(List.of(50L), List.of(100L), List.of(150L));

        listener.reconcileShippingBookings();
        listener.reconcileShippingBookings();
        listener.reconcileShippingBookings();

        ArgumentCaptor<Long> after = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> through = ArgumentCaptor.forClass(Long.class);
        verify(shipmentRepository, times(3)).findOrderIdsForProviderTracking(
                any(), any(), any(), after.capture(), through.capture(), any(Pageable.class)
        );
        assertThat(after.getAllValues()).containsExactly(0L, 50L, 0L);
        assertThat(through.getAllValues()).containsExactly(100L, 100L, 200L);
        verify(shippingService).reconcileChowdeckTracking(50L);
        verify(shippingService).reconcileChowdeckTracking(100L);
        verify(shippingService).reconcileChowdeckTracking(150L);
    }

    @Test
    void waybillTrackingReachesFrozenHighWaterBeforeIncludingNewerWaybills() {
        WaybillShippingService shippingService = mock(WaybillShippingService.class);
        WaybillShipmentRepository shipmentRepository = mock(WaybillShipmentRepository.class);
        WaybillFundedListener listener = new WaybillFundedListener(shippingService, shipmentRepository);
        ReflectionTestUtils.setField(listener, "reconciliationBatchSize", 1);
        when(shipmentRepository.findWaybillIdsDueForBooking(any(), any(), any()))
                .thenReturn(List.of());
        when(shipmentRepository.findProviderTrackingHighWaterWaybillId(any(), any(), any()))
                .thenReturn(100L, 200L);
        when(shipmentRepository.findWaybillIdsForProviderTracking(
                any(), any(), any(), anyLong(), anyLong(), any(Pageable.class)
        )).thenReturn(List.of(50L), List.of(100L), List.of(150L));

        listener.reconcileShippingBookings();
        listener.reconcileShippingBookings();
        listener.reconcileShippingBookings();

        ArgumentCaptor<Long> after = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> through = ArgumentCaptor.forClass(Long.class);
        verify(shipmentRepository, times(3)).findWaybillIdsForProviderTracking(
                any(), any(), any(), after.capture(), through.capture(), any(Pageable.class)
        );
        assertThat(after.getAllValues()).containsExactly(0L, 50L, 0L);
        assertThat(through.getAllValues()).containsExactly(100L, 100L, 200L);
        verify(shippingService).reconcileChowdeckTracking(50L);
        verify(shippingService).reconcileChowdeckTracking(100L);
        verify(shippingService).reconcileChowdeckTracking(150L);
    }
}
