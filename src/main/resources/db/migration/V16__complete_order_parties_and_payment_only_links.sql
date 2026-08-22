ALTER TABLE orders ADD COLUMN vendor_bank_account_last4 VARCHAR(4);
ALTER TABLE orders ADD COLUMN vendor_bank_code VARCHAR(20);
ALTER TABLE orders ADD COLUMN vendor_bank_name VARCHAR(255);
ALTER TABLE orders ADD COLUMN vendor_bank_account_name VARCHAR(255);
ALTER TABLE orders ADD COLUMN vendor_payout_recipient_code VARCHAR(100);

INSERT INTO order_status_history (order_id, from_status, to_status, reason, created_at)
SELECT id,
       'AWAITING_COUNTERPARTY',
       'CANCELLED',
       'Incomplete legacy draft closed during complete-at-creation migration',
       CURRENT_TIMESTAMP
FROM orders
WHERE status = 'AWAITING_COUNTERPARTY'
  AND payment_status = 'NOT_STARTED'
  AND total_amount IS NULL;

UPDATE orders
SET status = 'CANCELLED',
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'AWAITING_COUNTERPARTY'
  AND payment_status = 'NOT_STARTED'
  AND total_amount IS NULL;
