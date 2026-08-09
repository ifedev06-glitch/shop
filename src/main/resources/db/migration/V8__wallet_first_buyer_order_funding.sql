ALTER TABLE orders ADD COLUMN funding_mode VARCHAR(30);

UPDATE orders
SET funding_mode = CASE
    WHEN creator_role = 'BUYER' AND payment_reference IS NULL THEN 'WALLET'
    ELSE 'DIRECT_DVA'
END;

ALTER TABLE orders ALTER COLUMN funding_mode SET NOT NULL;
ALTER TABLE orders
    ADD CONSTRAINT chk_orders_funding_mode
    CHECK (funding_mode IN ('WALLET', 'DIRECT_DVA'));
CREATE INDEX idx_orders_creator_funding_status
    ON orders (creator_id, funding_mode, status);

ALTER TABLE wallet_transactions ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE wallet_transactions ADD COLUMN payment_receipt_id BIGINT;
ALTER TABLE wallet_transactions
    ADD CONSTRAINT fk_wallet_transaction_payment_receipt
    FOREIGN KEY (payment_receipt_id) REFERENCES payment_receipts (id);
CREATE UNIQUE INDEX idx_wallet_transactions_payment_receipt
    ON wallet_transactions (payment_receipt_id);

ALTER TABLE wallets
    ADD CONSTRAINT chk_wallets_nonnegative_balance CHECK (balance >= 0);
ALTER TABLE wallet_transactions
    ADD CONSTRAINT chk_wallet_transactions_positive_amount CHECK (amount > 0);
