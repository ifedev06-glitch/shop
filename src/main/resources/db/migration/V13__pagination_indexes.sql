CREATE INDEX idx_orders_creator_created_id
    ON orders (creator_id, created_at, id);

CREATE INDEX idx_wallet_transactions_wallet_created_id
    ON wallet_transactions (wallet_id, created_at, id);

DROP INDEX idx_wallet_withdrawals_wallet_created;
CREATE INDEX idx_wallet_withdrawals_wallet_created
    ON wallet_withdrawals (wallet_id, created_at, id);
