package com.example.shopsecure.provider.shipping;

import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.provider.common.WebhookPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TerminalAfricaShippingProviderTest {

    private static final String SECRET = "test-terminal-secret";
    private static final String BASE_URL = "https://api.terminal.test/v1";

    private MockRestServiceServer server;
    private TerminalAfricaShippingProvider provider;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        provider = new TerminalAfricaShippingProvider(
                restTemplate,
                JsonMapper.builder().build(),
                SECRET,
                BASE_URL + "/"
        );
    }

    @Test
    void validatesAddressAndMapsSuggestion() {
        server.expect(once(), requestTo(BASE_URL + "/addresses/validate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andExpect(content().json("""
                        {
                          "city":"Lagos",
                          "country":"NG",
                          "line1":"12 Broad Street",
                          "line2":"Suite 4",
                          "state":"Lagos",
                          "zip":"100001"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":{
                            "is_valid":true,
                            "suggestion_provided":true,
                            "suggested_address":{
                              "line1":"12 Broad St",
                              "city":"Lagos",
                              "country":"NG",
                              "zip":"100001"
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        AddressValidationResult result = provider.validateAddress(new ShipmentAddress(
                "12 Broad Street",
                "Suite 4",
                "Lagos",
                "Lagos",
                "NG",
                "100001"
        ));

        assertThat(result.valid()).isTrue();
        assertThat(result.suggestedAddress().line1()).isEqualTo("12 Broad St");
        assertThat(result.suggestedAddress().state()).isEqualTo("Lagos");
        server.verify();
    }

    @Test
    void acceptsLiveAddressValidationResponseWithoutData() {
        server.expect(once(), requestTo(BASE_URL + "/addresses/validate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "message":"Address is valid",
                          "data":null
                        }
                        """, MediaType.APPLICATION_JSON));

        AddressValidationResult result = provider.validateAddress(new ShipmentAddress(
                "1121 Allen Avenue, Ikeja",
                null,
                "Ikeja",
                "Lagos",
                "NG",
                "100281"
        ));

        assertThat(result.valid()).isTrue();
        assertThat(result.suggestedAddress()).isNull();
        server.verify();
    }

    @Test
    void getsAndMapsNigerianStatesAndCities() {
        server.expect(once(), requestTo(BASE_URL + "/states?country_code=NG"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":[{
                            "name":"Lagos",
                            "isoCode":"LA",
                            "countryCode":"NG",
                            "latitude":"6.465422",
                            "longitude":"3.406448"
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/cities?country_code=NG"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":[
                            {
                              "name":"Ikeja",
                              "stateCode":"LA",
                              "countryCode":"NG",
                              "latitude":"6.601838",
                              "longitude":"3.351486"
                            },
                            {
                              "name":"Port Harcourt",
                              "stateCode":"RI",
                              "countryCode":"NG"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThat(provider.getStates("ng"))
                .containsExactly(new ShippingState("Lagos", "LA", "NG"));
        assertThat(provider.getCities("ng"))
                .containsExactly(
                        new ShippingCity("Ikeja", "LA", "NG"),
                        new ShippingCity("Port Harcourt", "RI", "NG")
                );
        server.verify();
    }

    @Test
    void createsQuickShipmentWithDocumentedPayload() {
        server.expect(once(), requestTo(BASE_URL + "/shipments/quick"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "pickup_address":{
                            "first_name":"Ada",
                            "last_name":"Vendor",
                            "email":"vendor@example.com",
                            "phone":"+2348011111111",
                            "line1":"1 Vendor Street",
                            "city":"Lagos",
                            "state":"Lagos",
                            "country":"NG",
                            "zip":"100001"
                          },
                          "delivery_address":{
                            "first_name":"Tobi",
                            "last_name":"Buyer",
                            "email":"buyer@example.com",
                            "phone":"+2348022222222",
                            "line1":"2 Buyer Road",
                            "city":"Abuja",
                            "state":"FCT",
                            "country":"NG",
                            "zip":"900001"
                          },
                          "metadata":{"order_reference":"ORDER-123"},
                          "shipment_purpose":"commercial",
                          "parcel":{
                            "description":"Laptop computer",
                            "weight_unit":"kg",
                            "items":[{
                              "name":"Laptop",
                              "description":"Laptop computer",
                              "value":750000.00,
                              "currency":"NGN",
                              "quantity":1,
                              "weight":2.5,
                              "type":"parcel"
                            }]
                          }
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":{
                            "shipment_id":"SH-123",
                            "status":"draft",
                            "updated_at":"2026-08-07T10:15:30Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ShipmentResponse response = provider.createQuickShipment(request());

        assertThat(response.shipmentId()).isEqualTo("SH-123");
        assertThat(response.status()).isEqualTo(ShipmentStatus.DRAFT);
        assertThat(response.updatedAt()).isEqualTo(Instant.parse("2026-08-07T10:15:30Z"));
        server.verify();
    }

    @Test
    void normalizesDigitOnlyPhonesToE164WhenCreatingShipment() {
        server.expect(once(), requestTo(BASE_URL + "/shipments/quick"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "pickup_address":{
                            "first_name":"Ada",
                            "last_name":"Vendor",
                            "email":"vendor@example.com",
                            "phone":"+2348011111111"
                          },
                          "delivery_address":{
                            "first_name":"Tobi",
                            "last_name":"Buyer",
                            "email":"buyer@example.com",
                            "phone":"+2348022222222"
                          }
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":{
                            "shipment_id":"SH-123",
                            "status":"draft",
                            "updated_at":"2026-08-07T10:15:30Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        provider.createQuickShipment(new ShipmentRequest(
                new ShipmentAddress("1 Vendor Street", null, "Lagos", "Lagos", "NG", "100001"),
                new ShipmentAddress("2 Buyer Road", null, "Abuja", "FCT", "NG", "900001"),
                new ParcelDetails(
                        new BigDecimal("2.5"),
                        new BigDecimal("40"),
                        new BigDecimal("30"),
                        new BigDecimal("20"),
                        "Laptop computer",
                        "Laptop",
                        new BigDecimal("750000.00"),
                        "NGN",
                        1,
                        "parcel"
                ),
                new ShipmentContact("Ada", "Vendor", "vendor@example.com", "2348011111111"),
                new ShipmentContact("Tobi", "Buyer", "buyer@example.com", "2348022222222"),
                "ORDER-123"
        ));
        server.verify();
    }

    @Test
    void convertsLeadingZeroLocalPhonesUsingAddressCountry() {
        server.expect(once(), requestTo(BASE_URL + "/shipments/quick"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "pickup_address":{
                            "first_name":"Ada",
                            "last_name":"Vendor",
                            "email":"vendor@example.com",
                            "phone":"+2348011111111"
                          },
                          "delivery_address":{
                            "first_name":"Tobi",
                            "last_name":"Buyer",
                            "email":"buyer@example.com",
                            "phone":"+2348022222222"
                          }
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":{
                            "shipment_id":"SH-123",
                            "status":"draft",
                            "updated_at":"2026-08-07T10:15:30Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        provider.createQuickShipment(new ShipmentRequest(
                new ShipmentAddress("1 Vendor Street", null, "Lagos", "Lagos", "NG", "100001"),
                new ShipmentAddress("2 Buyer Road", null, "Abuja", "FCT", "NG", "900001"),
                new ParcelDetails(
                        new BigDecimal("2.5"),
                        new BigDecimal("40"),
                        new BigDecimal("30"),
                        new BigDecimal("20"),
                        "Laptop computer",
                        "Laptop",
                        new BigDecimal("750000.00"),
                        "NGN",
                        1,
                        "parcel"
                ),
                new ShipmentContact("Ada", "Vendor", "vendor@example.com", "+08011111111"),
                new ShipmentContact("Tobi", "Buyer", "buyer@example.com", "+23408022222222"),
                "ORDER-123"
        ));
        server.verify();
    }

    @Test
    void getsAndMapsShipmentRates() {
        server.expect(once(), requestTo(BASE_URL + "/rates/shipment?shipment_id=SH-123&currency=NGN"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":[{
                            "rate_id":"RT-123",
                            "id":"internal-rate-id",
                            "carrier_name":"DHL Express",
                            "carrier_logo":"https://assets.test/dhl.png",
                            "carrier_rate_description":"Express Worldwide",
                            "amount":14000,
                            "currency":"NGN",
                            "pickup_time":"Within 24 hours",
                            "delivery_time":"Within 3 days"
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        var rates = provider.getRates("SH-123", "NGN");

        assertThat(rates).singleElement().satisfies(rate -> {
            assertThat(rate.providerRateId()).isEqualTo("RT-123");
            assertThat(rate.providerInternalId()).isEqualTo("internal-rate-id");
            assertThat(rate.carrierName()).isEqualTo("DHL Express");
            assertThat(rate.providerAmount()).isEqualByComparingTo("14000");
            assertThat(rate.deliveryEstimate()).isEqualTo("Within 3 days");
        });
        server.verify();
    }

    @Test
    void excludesRatesThatRequireCustomerDropoff() {
        server.expect(once(), requestTo(BASE_URL + "/rates/shipment?shipment_id=SH-123&currency=NGN"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":[
                            {
                              "rate_id":"RT-DROPOFF",
                              "id":"dropoff-rate",
                              "carrier_name":"Dropoff Carrier",
                              "amount":5000,
                              "currency":"NGN",
                              "dropoff_required":true
                            },
                            {
                              "rate_id":"RT-PICKUP",
                              "id":"pickup-rate",
                              "carrier_name":"Pickup Carrier",
                              "amount":7000,
                              "currency":"NGN",
                              "dropoff_required":false
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThat(provider.getRates("SH-123", "NGN"))
                .singleElement()
                .satisfies(rate -> assertThat(rate.providerRateId()).isEqualTo("RT-PICKUP"));
        server.verify();
    }

    @Test
    void retrievesTerminalWalletBalance() {
        server.expect(once(), requestTo(BASE_URL + "/users/wallet-balance"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":{"amount":65000,"currency":"NGN"}
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThat(provider.getWalletBalance()).isEqualByComparingTo("65000");
        server.verify();
    }

    @Test
    void arrangesPickupTracksAndCancelsShipment() {
        server.expect(once(), requestTo(BASE_URL + "/shipments/pickup"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"shipment_id\":\"SH-123\",\"rate_id\":\"RT-123\"}"))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":{
                            "shipment_id":"SH-123",
                            "status":"confirmed",
                            "extras":{
                              "tracking_number":"TRACK-123",
                              "tracking_url":"https://tracking.test/TRACK-123"
                            },
                            "updated_at":"2026-08-07T11:00:00Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/shipments/track/SH-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":{
                            "shipment_id":"SH-123",
                            "status":"in_transit",
                            "extras":{
                              "tracking_number":"TRACK-123",
                              "tracking_url":"https://tracking.test/TRACK-123"
                            },
                            "events":[
                              {
                                "status":"confirmed",
                                "created_at":"2026-08-07T11:00:00Z",
                                "description":"Pickup arranged",
                                "location":"Lagos, NG"
                              },
                              {
                                "status":"in-transit",
                                "created_at":"2026-08-07T12:00:00Z",
                                "description":"Parcel collected",
                                "location":"Lagos, NG"
                              }
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/shipments/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"shipment_id\":\"SH-123\"}"))
                .andRespond(withSuccess("""
                        {
                          "status":true,
                          "data":{
                            "shipment_id":"SH-123",
                            "status":"canceled",
                            "updated_at":"2026-08-07T13:00:00Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ShipmentResponse arranged = provider.arrangePickup("SH-123", "RT-123");
        ShipmentTrackingStatus tracking = provider.trackShipment("SH-123");
        ShipmentResponse cancelled = provider.cancelShipment("SH-123");

        assertThat(arranged.status()).isEqualTo(ShipmentStatus.CONFIRMED);
        assertThat(arranged.trackingReference()).isEqualTo("TRACK-123");
        assertThat(arranged.trackingUrl()).isEqualTo("https://tracking.test/TRACK-123");
        assertThat(tracking.currentStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(tracking.lastUpdated()).isEqualTo(Instant.parse("2026-08-07T12:00:00Z"));
        assertThat(tracking.history()).hasSize(2);
        assertThat(tracking.trackingReference()).isEqualTo("TRACK-123");
        assertThat(tracking.trackingUrl()).isEqualTo("https://tracking.test/TRACK-123");
        assertThat(cancelled.status()).isEqualTo(ShipmentStatus.CANCELLED);
        server.verify();
    }

    @Test
    void verifiesExactWebhookBodyAndExtractsNestedShipmentEvent() throws Exception {
        String body = """
                {
                  "event":"shipment.updated",
                  "data":{
                    "data":{
                      "shipment":{
                        "shipment_id":"SH-123",
                        "events":[
                          {"status":"confirmed","created_at":"2026-08-07T11:00:00Z"},
                          {"status":"in_transit","created_at":"2026-08-07T12:00:00Z"}
                        ]
                      }
                    }
                  }
                }
                """;

        ShipmentStatusUpdatedEvent event = provider.handleWebhookEvent(new WebhookPayload(
                body,
                Map.of("x-terminal-signature", sign(body))
        ));

        assertThat(event.relevant()).isTrue();
        assertThat(event.shipmentId()).isEqualTo("SH-123");
        assertThat(event.status()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-07T12:00:00Z"));
    }

    @Test
    void derivesStatusFromWebhookEventNameAndIgnoresOtherEvents() throws Exception {
        String deliveredBody = """
                {
                  "event":"shipment.delivered",
                  "occurred_at":"2026-08-07T13:00:00Z",
                  "data":{"shipment":{"shipment_id":"SH-123"}}
                }
                """;
        ShipmentStatusUpdatedEvent delivered = provider.handleWebhookEvent(new WebhookPayload(
                deliveredBody,
                Map.of("X-Terminal-Signature", sign(deliveredBody))
        ));

        String ignoredBody = "{\"event\":\"address.updated\",\"data\":{}}";
        ShipmentStatusUpdatedEvent ignored = provider.handleWebhookEvent(new WebhookPayload(
                ignoredBody,
                Map.of("X-Terminal-Signature", sign(ignoredBody))
        ));

        assertThat(delivered.status()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(ignored.relevant()).isFalse();
    }

    @Test
    void rejectsRelevantWebhookWithoutParseableTimestamp() throws Exception {
        String missingTimestamp = """
                {"event":"shipment.cancelled","data":{"shipment":{"shipment_id":"SH-123"}}}
                """;
        String invalidTimestamp = """
                {
                  "event":"shipment.delivered",
                  "occurred_at":"not-a-timestamp",
                  "data":{"shipment":{"shipment_id":"SH-123"}}
                }
                """;

        assertThatThrownBy(() -> provider.handleWebhookEvent(new WebhookPayload(
                missingTimestamp,
                Map.of("X-Terminal-Signature", sign(missingTimestamp))
        ))).isInstanceOf(BusinessException.class)
                .hasMessage("Invalid Terminal Africa webhook timestamp");
        assertThatThrownBy(() -> provider.handleWebhookEvent(new WebhookPayload(
                invalidTimestamp,
                Map.of("X-Terminal-Signature", sign(invalidTimestamp))
        ))).isInstanceOf(BusinessException.class)
                .hasMessage("Invalid Terminal Africa webhook timestamp");
    }

    @Test
    void rejectsInvalidSignatureAndMalformedSignedPayload() throws Exception {
        String validBody = "{\"event\":\"shipment.created\",\"data\":{\"shipment_id\":\"SH-123\"}}";
        assertThatThrownBy(() -> provider.handleWebhookEvent(new WebhookPayload(
                validBody + " ",
                Map.of("X-Terminal-Signature", sign(validBody))
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid Terminal Africa webhook signature");

        String malformed = "{not-json";
        assertThatThrownBy(() -> provider.handleWebhookEvent(new WebhookPayload(
                malformed,
                Map.of("X-Terminal-Signature", sign(malformed))
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid Terminal Africa webhook payload");
    }

    @Test
    void convertsMalformedAndHttpProviderResponsesToSafeBusinessErrors() {
        server.expect(once(), requestTo(BASE_URL + "/shipments/track/SH-BAD"))
                .andRespond(withSuccess("{not-json", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/shipments/cancel"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .body("provider body must not escape")
                        .contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> provider.trackShipment("SH-BAD"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Terminal Africa returned an invalid response");

        assertThatThrownBy(() -> provider.cancelShipment("SH-123"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Terminal Africa request failed");
        server.verify();
    }

    @Test
    void convertsNetworkFailureToSafeBusinessErrorWithoutRetrying() {
        server.expect(once(), requestTo(BASE_URL + "/shipments/track/SH-123"))
                .andRespond(request -> {
                    throw new ResourceAccessException("network failure containing sensitive context");
                });

        assertThatThrownBy(() -> provider.trackShipment("SH-123"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Terminal Africa request failed");
        server.verify();
    }

    private ShipmentRequest request() {
        return new ShipmentRequest(
                new ShipmentAddress("1 Vendor Street", null, "Lagos", "Lagos", "NG", "100001"),
                new ShipmentAddress("2 Buyer Road", null, "Abuja", "FCT", "NG", "900001"),
                new ParcelDetails(
                        new BigDecimal("2.5"),
                        new BigDecimal("40"),
                        new BigDecimal("30"),
                        new BigDecimal("20"),
                        "Laptop computer",
                        "Laptop",
                        new BigDecimal("750000.00"),
                        "NGN",
                        1,
                        "parcel"
                ),
                new ShipmentContact("Ada", "Vendor", "vendor@example.com", "+2348011111111"),
                new ShipmentContact("Tobi", "Buyer", "buyer@example.com", "+2348022222222"),
                "ORDER-123"
        );
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
