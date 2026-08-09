# ShopSecure Backend API

This document describes the implemented backend contract. Provider secret keys must never be sent to a browser or mobile client.

## Pricing

The backend calculates all monetary values. Clients select a local shipping quote ID and never submit a total.

```text
customer delivery fee = Terminal rate + SHIPPING_DELIVERY_MARKUP
buyer total = item amount + customer delivery fee + SHIPPING_SERVICE_FEE
seller settlement = item amount
```

Defaults:

```text
SHIPPING_DELIVERY_MARKUP=1000.00
SHIPPING_SERVICE_FEE=500.00
SHIPPING_QUOTE_TTL=PT15M
```

Terminal's raw rate and the markup are internal. API responses expose the item amount, customer delivery fee, service fee, and total.

## Authentication

Member endpoints use:

```http
Authorization: Bearer <jwt>
```

Verified guest endpoints use:

```http
X-Order-Access-Token: <order-scoped-token>
```

Wallet-payment and delivery-confirmation mutations also require:

```http
Idempotency-Key: <unique-client-generated-value>
```

Guest tokens are stored as SHA-256 hashes, expire after `ORDER_GUEST_TOKEN_TTL`, and can be recovered through a new SMS/email OTP while the order is nonterminal.

## Order Roles

- Registered accounts have role `USER`.
- `creatorRole` is selected per order as `BUYER` or `VENDOR`.
- The share-link guest always has the opposite role.
- The vendor is always the shipment pickup party.
- The buyer is always the shipment delivery party and shipping-rate selector.
- A vendor supplies parcel details. A buyer cannot supply or change them.
- A guest vendor supplies a per-order external payout account.
- A guest buyer never supplies payout bank details.
- New buyer-created orders use `fundingMode: WALLET`; the registered buyer pays explicitly from their ShopSecure wallet.
- New vendor-created orders use `fundingMode: PAYSTACK_CHECKOUT`; the verified guest buyer opens an order-specific Paystack Checkout session.
- Every registered user keeps a personal DVA for wallet top-ups. Checkout does not replace virtual accounts.

## Addresses

Addresses require an ISO-2 country code and postal code:

```json
{
  "line1": "20 Admiralty Way",
  "line2": "Lekki Phase 1",
  "city": "Lagos",
  "state": "Lagos",
  "country": "NG",
  "postalCode": "101233"
}
```

Both pickup and delivery addresses are validated by Terminal Africa before a shipment draft or quote is created.

## Member Endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/api/auth/register` | Create an unverified account and email a verification code |
| `POST` | `/api/auth/verify-email` | Verify email, provision wallet/DVA, and issue the first JWT |
| `POST` | `/api/auth/resend-verification` | Replace and resend a signup verification code |
| `POST` | `/api/auth/login` | Login |
| `POST` | `/api/auth/forgot-password` | Request an email password-reset code; always returns a generic response |
| `POST` | `/api/auth/reset-password` | Consume the reset code and replace the password |
| `GET` | `/api/users/me` | Profile, wallet and virtual account |
| `PATCH` | `/api/users/me` | Complete profile and provision a missing DVA |
| `POST` | `/orders` | Create a buyer- or vendor-created order |
| `GET` | `/orders` | List creator orders |
| `GET` | `/orders/{orderId}` | Private creator view |
| `GET` | `/orders/{orderId}/history` | Status history |
| `POST` | `/orders/{orderId}/shipping/quotes` | Buyer creator requests quotes |
| `PUT` | `/orders/{orderId}/shipping/selection` | Buyer creator selects a local quote ID |
| `POST` | `/orders/{orderId}/payments/wallet` | Buyer creator explicitly funds a wallet-mode order; requires `Idempotency-Key` |
| `GET` | `/orders/{orderId}/shipping/tracking` | Refresh provider tracking |
| `POST` | `/orders/{orderId}/shipping/cancel` | Cancel an eligible unpaid order |
| `POST` | `/orders/{orderId}/confirm-delivery` | Confirm with `{"confirmation":true}` and initiate vendor payout |
| `POST` | `/api/bank-accounts/resolve` | Resolve an account for an authenticated member |
| `GET` | `/api/wallet` | Current wallet |
| `GET` | `/api/wallet/transactions` | Wallet ledger |

Vendor-created orders require `parcel` in the create request. Buyer-created orders reject it because the guest vendor must provide it.

## Public And Guest Endpoints

| Method | Endpoint | Access |
|---|---|---|
| `GET` | `/orders/token/{shareToken}` | Public safe order summary |
| `GET` | `/api/banks` | Public Paystack-supported Nigerian banks |
| `POST` | `/orders/token/{shareToken}/bank-account/resolve` | Public only while awaiting a guest vendor |
| `POST` | `/orders/token/{shareToken}/counterparty-info` | Public; stores guest details and sends OTP |
| `POST` | `/orders/token/{shareToken}/verify-otp` | Public; returns guest access token |
| `POST` | `/orders/token/{shareToken}/resend-otp` | Public |
| `POST` | `/orders/token/{shareToken}/access-recovery-otp` | Public; sends recovery OTP |
| `POST` | `/orders/token/{shareToken}/recover-access` | Public; rotates guest token |
| `POST` | `/orders/token/{shareToken}/shipping/quotes` | Verified guest buyer |
| `PUT` | `/orders/token/{shareToken}/shipping/selection` | Verified guest buyer |
| `POST` | `/orders/token/{shareToken}/payments/checkout` | Verified guest buyer; initialize or replay the active Paystack Checkout session |
| `GET` | `/orders/token/{shareToken}/status` | Verified guest |
| `POST` | `/orders/token/{shareToken}/delivery-otp` | Verified guest buyer |
| `POST` | `/orders/token/{shareToken}/confirm-delivery` | Verified guest buyer |

OTP challenges have a five-minute lifetime, a 60-second resend cooldown, a five-attempt limit, and are single-use. Order OTPs are sent through SMS and email. Signup verification and password-reset codes are email-only. WhatsApp is not used.

## Account Verification And Password Reset

- `POST /api/auth/register` returns `202` with `challengeId`, `emailMasked`, `expiresInSeconds`, and `message`. It does not return a JWT.
- New accounts cannot log in until `POST /api/auth/verify-email` succeeds.
- Wallet and Paystack DVA provisioning are deferred until email verification, preventing unverified signups from creating provider resources.
- Existing users are migration-backfilled as verified. Emails are normalized to lowercase and new writes must remain normalized.
- `POST /api/auth/forgot-password` always returns `202` with the same message whether the email exists or not.
- `POST /api/auth/reset-password` accepts `email`, a six-digit `code`, and `newPassword`. The new password must meet registration rules and differ from the current password.
- Successful password reset increments the user's auth version. JWTs issued before the reset are rejected immediately, even if their normal expiry has not passed.

## Payment Reconciliation

- Only signed Paystack charge events or authenticated server-to-server transaction verification responses are considered; browser callbacks are never payment proof.
- Checkout payments are matched by the unique provider reference persisted before the URL is returned.
- DVA transfers are matched by the receiving personal virtual account and normally credit that user's wallet.
- Multiple wallet or checkout orders for one creator can be in `AWAITING_PAYMENT`; equal amounts do not affect checkout matching.
- Provider event ID and status are unique in the receipt ledger, making retries idempotent while allowing a failed attempt to later succeed.
- After payment commits, the backend asynchronously sends both participants email and SMS. The vendor is told that buyer payment is confirmed, to prepare the item, and that the delivery partner will contact them with pickup details. The buyer is told that the order was created and payment confirmed, the vendor has been notified, and pickup is being arranged.
- These messages are attached to the one-time funded transition. Wallet idempotency replays and duplicate checkout callbacks do not resend them. Notification-provider failures are logged and never roll back payment or shipping.

Buyer-created `WALLET` orders:

- `POST /orders/{orderId}/payments/wallet` performs an all-or-nothing debit of the frozen buyer total.
- A sufficient balance returns `FUNDED`, confirms payment, and queues shipping booking.
- An insufficient balance performs no debit and returns the exact `shortfall` plus the creator DVA as `topUpInstructions`.
- A valid incoming DVA transfer credits the creator wallet once unless a migrated legacy `DIRECT_DVA` order still reserves that account.
- A wallet top-up never pays an order automatically. The buyer must call the wallet-payment endpoint again after the credit appears.
- The same successful payment `Idempotency-Key` replays the funded response. A different key cannot debit the same order again.
- An unpaid wallet-mode order can be cancelled while still in `AWAITING_PAYMENT` if no successful direct receipt exists.

Vendor-created `PAYSTACK_CHECKOUT` orders:

- Quote selection freezes the amount but returns no DVA payment instructions.
- `POST /orders/token/{shareToken}/payments/checkout` requires the guest token, initializes Paystack once, persists the attempt, and returns `authorizationUrl`, `accessCode`, and the unique reference. Immediate repeats return the same session. After `PAYMENT_CHECKOUT_RECHECK_AFTER` (15 minutes by default), the backend verifies the reference and creates a replacement only when Paystack reports `failed` or `abandoned`. Pending/unknown results and temporary verification failures return the existing URL and defer another provider check for the same interval.
- The callback URL returns the browser to the share page, but the callback itself never confirms payment.
- A signed `charge.success` or authenticated Paystack verification must match the persisted reference, exact amount, and currency before the order is funded and shipping is queued. Both use the provider transaction ID, so a later delivery of the same webhook is idempotent.
- Failed or abandoned attempts stay attributable to the order and permit a replacement checkout attempt. A late success on an older attempt can fund only that same order and supersedes an unpaid replacement. Pending or unknown verification never creates a second session.
- A second success after funding is recorded as `DUPLICATE` for refund review and never books shipping twice.
- The order can be cancelled after quote selection but before checkout initialization. Once any checkout URL has been issued, cancellation requires manual review because a late payment remains possible.
- Standard Checkout collects into ShopSecure's Paystack integration. It does not credit the seller's ShopSecure wallet or release seller funds at payment time.

Migrated `DIRECT_DVA` orders retain the old cumulative-transfer behavior so already-issued bank instructions and delayed transfers are not silently reclassified. No new order is created in this mode.

## Shipping

The Terminal adapter uses:

```text
POST /addresses/validate
POST /shipments/quick
GET  /rates/shipment
GET  /users/wallet-balance
POST /shipments/pickup
GET  /shipments/track/{shipmentId}
POST /shipments/cancel
```

After wallet funding, confirmed checkout, or exact legacy direct payment, the backend checks Terminal tracking before every booking attempt, then checks wallet liquidity and books the selected rate. This preflight covers both provider failures and a process crash around the pickup request: an already-active shipment is accepted instead of being booked twice. Retries use persisted backoff, bounded batches, and a configurable attempt limit; exhausted or provider-terminal shipments become `MANUAL_REVIEW`. Drop-off-only rates are not offered. Terminal delivery never releases seller funds by itself.

## Settlement

Buyer-created order:

```text
buyer confirms -> Paystack transfer of item amount -> transfer.success -> completed
```

Vendor-created order:

```text
guest buyer confirms with OTP -> item amount credited once to creator wallet -> completed
```

Paystack payout references are deterministic. Pending or unknown dispatch outcomes are reconciled on a schedule through transfer verification. Confirmed failures are retried with a new provider reference up to `PAYOUT_MAX_ATTEMPTS`; exhaustion becomes `REQUIRES_ACTION`. Reversal is terminal and cannot be overwritten by a replayed success event. Production is configured for fully automated transfers and therefore requires transfer OTP to be disabled in the ShopSecure Paystack account. An unexpected `otp` response remains `REQUIRES_ACTION` and never completes the order.

After settlement commits, both participants receive email and SMS. The vendor is told that the buyer confirmed delivery and that the item amount was credited to the verified bank account or ShopSecure wallet. The buyer is thanked for confirming delivery and told that the vendor was credited. External-payout notifications are sent only after Paystack reports transfer success; pending or unknown payouts do not claim that funds were credited. Idempotent confirmation replays and duplicate payout-success events do not resend these messages.

## Webhooks

| Method | Endpoint | Verification |
|---|---|---|
| `POST` | `/api/webhooks/paystack` | `x-paystack-signature`, HMAC-SHA512 |
| `POST` | `/api/webhooks/terminal` | `X-Terminal-Signature`, HMAC-SHA512 |

Signatures are calculated over the exact raw body. Only these two POST paths are public. Neighboring paths and other HTTP methods require member authentication.

Order funding commits before shipment booking begins. The post-commit booking attempt runs asynchronously so Paystack webhook responses do not wait on Terminal Africa network calls; persisted scheduled reconciliation retries failed or interrupted attempts.

## Operational Limitations

- Reusable-DVA order correlation remains a risk only for migrated `DIRECT_DVA` orders. New vendor-created orders use unique checkout references.
- Paystack refund and dispute events do not currently create compensating wallet ledger entries. Do not issue a provider-side refund without the corresponding manual ShopSecure ledger/order reconciliation.
- V8 does not automatically reclassify successful receipts that were already stored as `UNMATCHED` before upgrade. Audit any such production receipts before enabling wallet spending.

## Required Production Configuration

```text
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:postgresql://<host>:5432/<database>
DB_USERNAME=<database-user>
DB_PASSWORD=<database-password>
JWT_SECRET=<Base64-encoded secret of at least 32 bytes>
JWT_EXPIRATION=3600000
APP_BASE_URL=https://<public-frontend-host>
DATA_ENCRYPTION_KEY=<Base64-encoded 32-byte key>
ORDER_GUEST_TOKEN_TTL=PT168H
PAYSTACK_SECRET_KEY=<live secret>
TERMINAL_AFRICA_ENABLED=true
TERMINAL_AFRICA_BASE_URL=https://api.terminal.africa/v1
TERMINAL_AFRICA_SECRET_KEY=<live secret>
SHIPPING_DELIVERY_MARKUP=1000.00
SHIPPING_SERVICE_FEE=500.00
SHIPPING_QUOTE_TTL=PT15M
TWILIO_SMS_ENABLED=true
TWILIO_ACCOUNT_SID=<sid>
TWILIO_AUTH_TOKEN=<token>
TWILIO_FROM_NUMBER=<sender>
RESEND_API_KEY=<key>
```

`PAYSTACK_VIRTUAL_ACCOUNT_BANK` is optional when Paystack can choose the dedicated-account bank. Reconciliation delays and `PAYOUT_MAX_ATTEMPTS` can also be overridden; see `.env.example`.

When `PAYSTACK_SECRET_KEY` starts with `sk_test_`, the bank list exposes only Paystack Test Bank (`001`); use account number `0000000000`. This avoids Paystack's daily cap on resolving live bank accounts in test mode. When creating the test transfer recipient, the backend translates Paystack's account-resolution code (`001`) to its documented Zenith transfer-test code (`057`). Live keys continue using the real Nigerian bank list without translation.

The Docker image activates the `prod` profile. Every run must select exactly one of `local`, `test`, or `prod`; missing, misspelled, and combined profiles are rejected. Production startup rejects disabled Terminal/Twilio providers, invalid security lifetimes, non-HTTPS provider/application URLs, unsafe reconciliation limits, missing provider credentials, and any Paystack secret that is not an `sk_live_` key. Flyway baselining is disabled in production so an unexpected nonempty schema fails closed. Terminal's wallet must be funded separately; Paystack collections do not automatically top it up. Register both webhook URLs with their providers before accepting live orders.

## Migration Notes

Flyway V7 creates the guest-access, parcel, shipment, quote, payment-receipt, settlement and webhook-deduplication schema. All legacy workflow orders become `MANUAL_REVIEW`: their old address snapshots have no required postal code, and an old wallet debit is not proof of recoverable provider-held funds.

Flyway V8 adds `orders.funding_mode`, receipt-backed DVA wallet credits, and wallet order-payment debits. Existing buyer orders that already have a payment reference remain `DIRECT_DVA`; unselected buyer orders become `WALLET`; vendor-created orders remain `DIRECT_DVA`. Wallet balances are constrained to remain nonnegative.

The V8 balance and transaction-amount checks intentionally fail closed if populated V7 data violates the new financial invariants. Preflight production data for negative wallet balances and nonpositive wallet transaction amounts before deployment.

Flyway V9 adds `PAYSTACK_CHECKOUT` and persisted order payment attempts. Unselected vendor-created orders with no issued payment reference move safely to checkout. Selected or historical `DIRECT_DVA` orders remain unchanged to preserve delayed-transfer handling.
