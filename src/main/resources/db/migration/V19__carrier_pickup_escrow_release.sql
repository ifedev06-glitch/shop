ALTER TABLE orders
    ADD COLUMN escrow_release_policy VARCHAR(40) NOT NULL
        DEFAULT 'BUYER_DELIVERY_CONFIRMATION';
ALTER TABLE orders
    ADD COLUMN carrier_pickup_confirmed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE orders
    ADD CONSTRAINT chk_orders_escrow_release_policy
        CHECK (escrow_release_policy IN ('BUYER_DELIVERY_CONFIRMATION', 'CARRIER_PICKUP'));

ALTER TABLE order_settlements
    ADD COLUMN trigger_type VARCHAR(40) NOT NULL
        DEFAULT 'BUYER_DELIVERY_CONFIRMATION';
ALTER TABLE order_settlements
    ADD COLUMN triggered_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE order_settlements
    ADD COLUMN next_reconciliation_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE order_settlements
    ADD COLUMN reconciliation_token VARCHAR(64);
ALTER TABLE order_settlements
    ADD COLUMN unexpected_payout_reference VARCHAR(255);
ALTER TABLE order_settlements
    ADD COLUMN unexpected_transfer_id VARCHAR(100);
UPDATE order_settlements
SET triggered_at = created_at
WHERE triggered_at IS NULL;
UPDATE order_settlements
SET next_reconciliation_at = CURRENT_TIMESTAMP
WHERE status IN ('PENDING', 'DISPATCH_UNKNOWN');
UPDATE orders
SET settlement_status = 'REQUIRES_ACTION',
    updated_at = CURRENT_TIMESTAMP
WHERE id IN (
    SELECT order_id
    FROM order_settlements
    WHERE status = 'FAILED'
);
UPDATE order_settlements
SET status = 'REQUIRES_ACTION',
    failure_reason = COALESCE(failure_reason, 'Legacy failed payout requires manual review'),
    next_reconciliation_at = NULL
WHERE status = 'FAILED';
ALTER TABLE order_settlements ALTER COLUMN triggered_at SET NOT NULL;
ALTER TABLE order_settlements
    ADD CONSTRAINT chk_order_settlements_trigger
        CHECK (trigger_type IN ('BUYER_DELIVERY_CONFIRMATION', 'CARRIER_PICKUP'));
CREATE INDEX idx_order_settlements_reconciliation_due
    ON order_settlements (settlement_type, status, next_reconciliation_at, id);
CREATE UNIQUE INDEX idx_order_settlements_transfer_id
    ON order_settlements (transfer_id);
CREATE UNIQUE INDEX idx_wallet_withdrawals_transfer_id
    ON wallet_withdrawals (transfer_id);

ALTER TABLE order_tracking_notifications
    ADD COLUMN notification_type VARCHAR(40) NOT NULL DEFAULT 'TRACKING_READY';
ALTER TABLE order_tracking_notifications
    DROP CONSTRAINT uq_order_tracking_notification_role;
ALTER TABLE order_tracking_notifications
    ADD CONSTRAINT uq_order_tracking_notification_role_type
        UNIQUE (order_id, participant_role, notification_type);
ALTER TABLE order_tracking_notifications
    ADD CONSTRAINT chk_order_tracking_notification_type
        CHECK (notification_type IN ('TRACKING_READY', 'CARRIER_PICKUP_CONFIRMED'));
