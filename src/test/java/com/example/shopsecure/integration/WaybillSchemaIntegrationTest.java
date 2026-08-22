package com.example.shopsecure.integration;

import com.example.shopsecure.ShopsecureApplication;
import com.example.shopsecure.entity.WaybillShipment;
import com.example.shopsecure.entity.WaybillStatus;
import com.example.shopsecure.provider.shipping.ShipmentStatus;
import com.example.shopsecure.repository.WaybillRepository;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = {
                ShopsecureApplication.class,
                TestConfig.class,
                ReconciliationSettlementTestConfig.class
        },
        properties = {
                "spring.datasource.url=jdbc:h2:mem:waybillschema;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "payout.reconciliation.initial-delay-ms=86400000",
                "wallet.withdrawal.reconciliation.initial-delay-ms=86400000",
                "shipping.booking-reconciliation.initial-delay-ms=86400000",
                "waybill.booking-reconciliation.initial-delay-ms=86400000",
                "waybill.cancellation-reconciliation.initial-delay-ms=86400000",
                "waybill.payment-reconciliation.initial-delay-ms=86400000",
                "waybill.refund-reconciliation.initial-delay-ms=86400000"
        }
)
@ActiveProfiles("test")
class WaybillSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WaybillRepository waybillRepository;

    @Test
    void v15BootsInH2PostgreSqlModeWithHardenedColumns() {
        assertThat(nullable("WAYBILL_PAYMENT_ATTEMPTS", "AMOUNT")).isEqualTo("NO");
        assertThat(nullable("WAYBILL_PAYMENT_ATTEMPTS", "CURRENCY")).isEqualTo("NO");
        assertThat(nullable("WAYBILL_INVITATIONS", "OTP_SEND_COUNT")).isEqualTo("NO");
        assertThat(nullable("WAYBILL_INVITATIONS", "OTP_FAILED_ATTEMPT_COUNT"))
                .isEqualTo("NO");
        assertThat(columnDefault("WAYBILL_INVITATIONS", "OTP_SEND_COUNT")).contains("0");
        assertThat(columnDefault("WAYBILL_INVITATIONS", "OTP_FAILED_ATTEMPT_COUNT"))
                .contains("0");
        assertThat(nullable("WAYBILL_SHIPMENTS", "CANCELLATION_ATTEMPT")).isEqualTo("NO");
        assertThat(columnDefault("WAYBILL_SHIPMENTS", "CANCELLATION_ATTEMPT")).contains("0");
        assertThat(characterMaximumLength(
                "WAYBILL_SHIPMENTS",
                "CANCELLATION_FAILURE_REASON"
        )).isEqualTo(500L);
        assertThat(checkClause("CHK_WAYBILL_CANCELLATION_ATTEMPT"))
                .containsIgnoringCase("CANCELLATION_ATTEMPT")
                .contains(">= 0");
        assertThat(indexColumns("IDX_WAYBILL_SHIPMENTS_CANCELLATION_DUE"))
                .containsExactly("STATUS", "NEXT_CANCELLATION_ATTEMPT_AT");
        assertThat(nullable("WAYBILL_EMAIL_NOTIFICATIONS", "RECIPIENT_EMAIL")).isEqualTo("NO");
        assertThat(nullable("WAYBILL_EMAIL_NOTIFICATIONS", "ATTEMPT_COUNT")).isEqualTo("NO");
        assertThat(columnDefault("WAYBILL_EMAIL_NOTIFICATIONS", "STATUS")).contains("PENDING");
        assertThat(columnDefault("WAYBILL_EMAIL_NOTIFICATIONS", "ATTEMPT_COUNT")).contains("0");
        assertThat(nullable("WAYBILL_EMAIL_NOTIFICATIONS", "DISPATCH_TOKEN")).isEqualTo("YES");
        assertThat(nullable("WAYBILL_EMAIL_NOTIFICATIONS", "FIRST_ATTEMPT_AT")).isEqualTo("YES");
        assertThat(checkClause("CHK_WAYBILL_EMAIL_NOTIFICATION_STATUS"))
                .contains("SKIPPED");
        assertThat(checkClause("CHK_WAYBILL_EMAIL_NOTIFICATION_ATTEMPTS"))
                .containsIgnoringCase("ATTEMPT_COUNT")
                .contains(">= 0");
        assertThat(indexColumns("IDX_WAYBILL_EMAIL_NOTIFICATIONS_DUE"))
                .containsExactly("STATUS", "NEXT_ATTEMPT_AT");
    }

    @Test
    void v19PersistsReleasePolicySettlementTriggerAndTypedOrderNotifications() {
        assertThat(nullable("ORDERS", "ESCROW_RELEASE_POLICY")).isEqualTo("NO");
        assertThat(columnDefault("ORDERS", "ESCROW_RELEASE_POLICY"))
                .contains("BUYER_DELIVERY_CONFIRMATION");
        assertThat(nullable("ORDERS", "CARRIER_PICKUP_CONFIRMED_AT")).isEqualTo("YES");
        assertThat(checkClause("CHK_ORDERS_ESCROW_RELEASE_POLICY"))
                .contains("CARRIER_PICKUP");
        assertThat(nullable("ORDER_SETTLEMENTS", "TRIGGER_TYPE")).isEqualTo("NO");
        assertThat(nullable("ORDER_SETTLEMENTS", "TRIGGERED_AT")).isEqualTo("NO");
        assertThat(nullable("ORDER_SETTLEMENTS", "NEXT_RECONCILIATION_AT")).isEqualTo("YES");
        assertThat(nullable("ORDER_SETTLEMENTS", "RECONCILIATION_TOKEN")).isEqualTo("YES");
        assertThat(nullable("ORDER_SETTLEMENTS", "UNEXPECTED_PAYOUT_REFERENCE")).isEqualTo("YES");
        assertThat(nullable("ORDER_SETTLEMENTS", "UNEXPECTED_TRANSFER_ID")).isEqualTo("YES");
        assertThat(checkClause("CHK_ORDER_SETTLEMENTS_TRIGGER"))
                .contains("CARRIER_PICKUP");
        assertThat(indexColumns("IDX_ORDER_SETTLEMENTS_RECONCILIATION_DUE"))
                .containsExactly("SETTLEMENT_TYPE", "STATUS", "NEXT_RECONCILIATION_AT", "ID");
        assertThat(indexColumns("IDX_ORDER_SETTLEMENTS_TRANSFER_ID"))
                .containsExactly("TRANSFER_ID");
        assertThat(indexUnique("IDX_ORDER_SETTLEMENTS_TRANSFER_ID")).isTrue();
        assertThat(indexColumns("IDX_WALLET_WITHDRAWALS_TRANSFER_ID"))
                .containsExactly("TRANSFER_ID");
        assertThat(indexUnique("IDX_WALLET_WITHDRAWALS_TRANSFER_ID")).isTrue();
        assertThat(nullable("ORDER_TRACKING_NOTIFICATIONS", "NOTIFICATION_TYPE"))
                .isEqualTo("NO");
        assertThat(checkClause("CHK_ORDER_TRACKING_NOTIFICATION_TYPE"))
                .contains("CARRIER_PICKUP_CONFIRMED");
    }

    @Test
    void cancellationRetryEntityAndDueQueryAreValid() throws NoSuchFieldException {
        assertThat(WaybillShipment.class.getDeclaredField("cancellationAttempt")
                .getAnnotation(PositiveOrZero.class)).isNotNull();
        assertThat(WaybillShipment.class.getDeclaredField("cancellationFailureReason")
                .getAnnotation(Size.class).max()).isEqualTo(500);
        assertThat(waybillRepository.findWaybillIdsDueForCancellation(
                WaybillStatus.CANCELLATION_PENDING,
                Set.of(
                        ShipmentStatus.DRAFT,
                        ShipmentStatus.PENDING,
                        ShipmentStatus.CONFIRMED
                ),
                LocalDateTime.now(ZoneOffset.UTC),
                PageRequest.of(0, 25)
        )).isEmpty();
    }

    private String nullable(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                String.class,
                table,
                column
        );
    }

    private String columnDefault(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                String.class,
                table,
                column
        );
    }

    private Long characterMaximumLength(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                Long.class,
                table,
                column
        );
    }

    private String checkClause(String constraint) {
        return jdbcTemplate.queryForObject(
                "SELECT CHECK_CLAUSE FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS "
                        + "WHERE CONSTRAINT_NAME = ?",
                String.class,
                constraint
        );
    }

    private List<String> indexColumns(String index) {
        return jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                        + "WHERE INDEX_NAME = ? ORDER BY ORDINAL_POSITION",
                String.class,
                index
        );
    }

    private Boolean indexUnique(String index) {
        return jdbcTemplate.queryForObject(
                "SELECT IS_UNIQUE FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                        + "WHERE INDEX_NAME = ? FETCH FIRST 1 ROW ONLY",
                Boolean.class,
                index
        );
    }
}
