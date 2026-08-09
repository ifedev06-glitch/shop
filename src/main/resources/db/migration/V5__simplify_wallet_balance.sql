ALTER TABLE wallets RENAME COLUMN available_balance TO balance;
ALTER TABLE wallets DROP COLUMN processing_balance;

ALTER TABLE wallet_transactions RENAME COLUMN available_balance_before TO balance_before;
ALTER TABLE wallet_transactions RENAME COLUMN available_balance_after TO balance_after;
ALTER TABLE wallet_transactions DROP COLUMN processing_balance_before;
ALTER TABLE wallet_transactions DROP COLUMN processing_balance_after;
