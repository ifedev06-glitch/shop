UPDATE users
SET email = 'legacy-user-' || id || '@invalid.shopsecure.local'
WHERE email IS NULL OR TRIM(email) = '';

UPDATE users SET email = LOWER(TRIM(email));

ALTER TABLE users ALTER COLUMN email SET NOT NULL;
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMP;
ALTER TABLE users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
UPDATE users SET email_verified_at = COALESCE(created_at, CURRENT_TIMESTAMP);
ALTER TABLE users ADD CONSTRAINT chk_users_email_normalized
    CHECK (email = LOWER(TRIM(email)));

ALTER TABLE otp_challenges ALTER COLUMN phone_number DROP NOT NULL;
ALTER TABLE otp_challenges ADD COLUMN email VARCHAR(255);
CREATE INDEX idx_otp_challenges_expires_at ON otp_challenges (expires_at);
