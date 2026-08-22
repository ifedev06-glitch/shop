package com.example.shopsecure.provider.shipping;

import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChowdeckShippingProviderTest {

    private static final String SECRET = "sk_test_chowdeck";
    private static final String BASE_URL = "https://api.chowdeck.test";

    private MockRestServiceServer server;
    private ChowdeckShippingProvider provider;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        provider = new ChowdeckShippingProvider(
                restTemplate,
                JsonMapper.builder().build(),
                SECRET,
                BASE_URL + "/",
                Duration.ofMinutes(5),
                false
        );
    }

    @Test
    void quotesRelayFeeAndConvertsKoboToNaira() {
        Instant before = Instant.now();
        server.expect(once(), requestTo(BASE_URL + "/relay/delivery/fee"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "source_address_string":"1 Vendor Street, Ikeja, Lagos, 100001, Nigeria",
                          "destination_address_string":"2 Buyer Road, Lekki, Lagos, 101233, Nigeria",
                          "estimated_order_amount":75000000
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "status":"success",
                          "data":{
                            "id":240140913,
                            "total_amount":185000,
                            "available_delivery_hours":["0800-1800"]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ShippingProvider.ShippingQuoteResult result = provider.quoteShipment(
                "order-123",
                request(),
                "NGN"
        );

        assertThat(result.rates()).singleElement().satisfies(rate -> {
            assertThat(rate.providerRateId()).isEqualTo("240140913");
            assertThat(rate.carrierName()).isEqualTo("Chowdeck");
            assertThat(rate.providerAmount()).isEqualByComparingTo("1850.00");
            assertThat(rate.currency()).isEqualTo("NGN");
        });
        assertThat(result.expiresAt()).isBetween(
                before.plus(Duration.ofMinutes(5)),
                Instant.now().plus(Duration.ofMinutes(5))
        );
        server.verify();
    }

    @Test
    void createsRelayDeliveryWithContactsAndFeeId() {
        server.expect(once(), requestTo(BASE_URL + "/relay/delivery"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andExpect(content().json("""
                        {
                          "destination_contact":{
                            "name":"Tobi Buyer",
                            "phone":"08022222222",
                            "country_code":"NG",
                            "email":"buyer@example.com"
                          },
                          "source_contact":{
                            "name":"Ada Vendor",
                            "phone":"08011111111",
                            "country_code":"NG",
                            "email":"vendor@example.com"
                          },
                          "fee_id":240140913,
                          "item_type":"parcel",
                          "user_action":"sending",
                          "reference":"order-123",
                          "estimated_order_amount":75000000,
                          "customer_delivery_note":"Laptop"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "status":"success",
                          "data":{
                            "reference":"order-123",
                            "made_payment":true,
                            "status":"preparing",
                            "updated_at":"2026-08-19T09:30:00Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ShipmentResponse response = provider.arrangePickup(
                "order-123",
                "240140913",
                request()
        );

        assertThat(response.shipmentId()).isEqualTo("order-123");
        assertThat(response.status()).isEqualTo(ShipmentStatus.PENDING);
        assertThat(response.updatedAt()).isEqualTo(Instant.parse("2026-08-19T09:30:00Z"));
        server.verify();
    }

    @Test
    void marksDestinationInitiatedDeliveryAsReceiving() {
        server.expect(once(), requestTo(BASE_URL + "/relay/delivery"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"user_action":"receiving","reference":"order-123"}
                        """))
                .andRespond(withSuccess("""
                        {
                          "status":"success",
                          "data":{
                            "reference":"order-123",
                            "made_payment":true,
                            "status":"preparing"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ShipmentResponse response = provider.arrangePickup(
                "order-123",
                "240140913",
                request(ShipmentUserAction.RECEIVING)
        );

        assertThat(response.status()).isEqualTo(ShipmentStatus.PENDING);
        server.verify();
    }

    @Test
    void reconcilesDuplicateCreationThroughTracking() {
        server.expect(once(), requestTo(BASE_URL + "/relay/delivery"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"status":"error","message":"Possible duplicate delivery reference"}
                                """));
        server.expect(once(), requestTo(BASE_URL + "/relay/delivery/order-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "status":"success",
                          "data":{
                            "reference":"order-123",
                            "status":"awaiting_pickup",
                            "time_payment_confirmed":"2026-08-19T09:30:00Z",
                            "updated_at":"2026-08-19T09:31:00Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ShipmentResponse response = provider.arrangePickup(
                "order-123",
                "240140913",
                request()
        );

        assertThat(response.status()).isEqualTo(ShipmentStatus.CONFIRMED);
        server.verify();
    }

    @Test
    void mapsTrackingTimelineAndRejectsNoIdentity() {
        server.expect(once(), requestTo(BASE_URL + "/relay/delivery/order-123"))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andRespond(withSuccess("""
                        {
                          "status":"success",
                          "data":{
                            "reference":"order-123",
                            "status":"picked_up",
                            "updated_at":"2026-08-19T10:00:00Z",
                            "timeline":[{
                              "status":"awaiting_pickup",
                              "description":"Rider assigned",
                              "created_at":"2026-08-19T09:30:00Z"
                            }]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ShipmentTrackingStatus tracking = provider.trackShipment("order-123");

        assertThat(tracking.currentStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(tracking.trackingReference()).isEqualTo("order-123");
        assertThat(tracking.history()).singleElement().satisfies(checkpoint -> {
            assertThat(checkpoint.status()).isEqualTo(ShipmentStatus.CONFIRMED);
            assertThat(checkpoint.description()).isEqualTo("Rider assigned");
        });
        server.verify();
    }

    @Test
    void canonicalTrackingRejectsMismatchedReference() {
        server.expect(once(), requestTo(BASE_URL + "/relay/delivery/order-123"))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andRespond(withSuccess("""
                        {
                          "status":"success",
                          "data":{
                            "reference":"order-999",
                            "status":"picked_up",
                            "updated_at":"2026-08-19T10:00:00Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.trackShipment("order-123"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Chowdeck returned a mismatched delivery");
        server.verify();
    }

    @Test
    void readsRelayWalletBalanceInNaira() {
        server.expect(once(), requestTo(BASE_URL + "/relay/wallet/balance"))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"balance":123456}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(provider.getWalletBalance()).isEqualByComparingTo(new BigDecimal("1234.56"));
        server.verify();
    }

    @Test
    void cancelsRelayDeliveryByPersistedReference() {
        server.expect(once(), requestTo(BASE_URL + "/relay/delivery/order-123/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"reason":"Customer no longer needs delivery"}
                        """))
                .andRespond(withSuccess("""
                        {
                          "status":"success",
                          "data":{
                            "reference":"order-123",
                            "updated_at":"2026-08-19T10:15:00Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ShipmentResponse response = provider.cancelShipment(
                "order-123",
                "Customer no longer needs delivery"
        );

        assertThat(response.status()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(response.shipmentId()).isEqualTo("order-123");
        assertThat(response.updatedAt()).isEqualTo(Instant.parse("2026-08-19T10:15:00Z"));
        server.verify();
    }

    @Test
    void reconcilesAmbiguousCancellationBeforeRetrying() {
        server.expect(once(), requestTo(BASE_URL + "/relay/delivery/order-123/cancel"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"status":"error","message":"Delivery is already cancelled"}
                                """));
        server.expect(once(), requestTo(BASE_URL + "/relay/delivery/order-123"))
                .andRespond(withSuccess("""
                        {
                          "status":"success",
                          "data":{
                            "reference":"order-123",
                            "status":"cancelled",
                            "updated_at":"2026-08-19T10:15:00Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ShipmentResponse response = provider.cancelShipment(
                "order-123",
                "Customer no longer needs delivery"
        );

        assertThat(response.status()).isEqualTo(ShipmentStatus.CANCELLED);
        server.verify();
    }

    @Test
    void hardKillPreventsAnyProviderCall() {
        ChowdeckShippingProvider disabled = new ChowdeckShippingProvider(
                new RestTemplate(),
                JsonMapper.builder().build(),
                SECRET,
                BASE_URL,
                Duration.ofMinutes(5),
                true
        );

        assertThatThrownBy(() -> disabled.quoteShipment("order-123", request(), "NGN"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Chowdeck shipping is disabled");
    }

    private ShipmentRequest request() {
        return request(ShipmentUserAction.SENDING);
    }

    private ShipmentRequest request(ShipmentUserAction userAction) {
        return new ShipmentRequest(
                new ShipmentAddress(
                        "1 Vendor Street",
                        null,
                        "Ikeja",
                        "Lagos",
                        "NG",
                        "100001"
                ),
                new ShipmentAddress(
                        "2 Buyer Road",
                        null,
                        "Lekki",
                        "Lagos",
                        "NG",
                        "101233"
                ),
                new ParcelDetails(
                        new BigDecimal("2.50"),
                        new BigDecimal("30"),
                        new BigDecimal("20"),
                        new BigDecimal("10"),
                        "Laptop",
                        "Laptop computer",
                        new BigDecimal("750000.00"),
                        "NGN",
                        1,
                        "parcel"
                ),
                new ShipmentContact("Ada", "Vendor", "vendor@example.com", "+2348011111111"),
                new ShipmentContact("Tobi", "Buyer", "buyer@example.com", "+2348022222222"),
                "order-123",
                userAction
        );
    }
}
