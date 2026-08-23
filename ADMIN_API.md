# ShopSecure Admin API

The admin API exposes read-only operational data plus one audited Chowdeck shipping control. It uses the existing JWT login flow. Every route requires a verified user whose role is `ADMIN`. Public registration continues to create only `USER` accounts.

Frontend implementers should use the `API Enums`, `Shared TypeScript Contract`, `CORS And Rate Limits`, `Admin Dashboard Frontend`, and `Security And Privacy Checklist` sections of `FRONTEND_API_HANDOFF.md`.

Responses handled by `AdminController` set `Cache-Control: no-store` and `Pragma: no-cache`. Security-layer `401`/`403` responses can occur before those headers are applied. Passwords, raw OTP data, share/invitation tokens, guest/waybill access tokens, payment access codes, authorization URLs, idempotency keys, encrypted bank data, payout recipient codes and full account numbers are never returned.

Flyway V19 adds pickup-settlement audit and recovery fields. Order summaries expose `escrowReleasePolicy` and `carrierPickupConfirmedAt`; settlement rows expose `trigger`, `triggeredAt`, `nextReconciliationAt`, and durable unexpected provider reference/transfer IDs when a response mismatch stops automation. New orders use `CARRIER_PICKUP`, while migrated orders retain their persisted legacy policy. A V19 order can have root status `IN_TRANSIT` with settlement `COMPLETED`, meaning the vendor bank payout succeeded while carrier delivery is still in progress. Definitive payout rejection/failure, mismatched references or transfer IDs, and successful responses with the wrong amount, currency, or frozen recipient become `REQUIRES_ACTION`; the backend does not automatically dispatch a second payout reference.

Flyway V20 adds read-only order-dispute operations. Disputes are notification-only: their presence does not pause or modify shipment, settlement, payout, refund, payment, or order status. Admins can inspect the submitted reason and per-recipient email state, but V20 provides no dispute mutation.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/admin/dashboard` | Membership, order, financial and operational metrics |
| `GET` | `/api/admin/users` | Paginated users |
| `GET` | `/api/admin/users/{userId}` | Masked profile, wallet, virtual account and bank summary |
| `GET` | `/api/admin/orders` | Paginated global order list |
| `GET` | `/api/admin/orders/{orderId}` | Order, parties, parcel, shipment, settlement, payment and history detail |
| `GET` | `/api/admin/disputes` | Paginated order disputes with bounded reason previews |
| `GET` | `/api/admin/disputes/{disputeId}` | Full reason, masked parties and notification delivery states |
| `GET` | `/api/admin/waybills` | Paginated global V14 waybill list |
| `GET` | `/api/admin/waybills/{waybillId}` | Waybill, parties, parcel, shipment, quotes, payments, refunds, invitations and history |
| `GET` | `/api/admin/wallet-transactions` | Paginated wallet ledger |
| `GET` | `/api/admin/withdrawals` | Paginated withdrawals |
| `GET` | `/api/admin/settlements` | Paginated order settlements |
| `GET` | `/api/admin/shipments` | Paginated shipments |
| `GET` | `/api/admin/payment-attempts` | Paginated checkout attempts |
| `GET` | `/api/admin/payment-receipts` | Paginated payment receipts and classifications |
| `GET` | `/api/admin/shipping/chowdeck-control` | Current configured/effective Chowdeck mode |
| `PUT` | `/api/admin/shipping/chowdeck-control` | Audited compare-and-set mode update |
| `GET` | `/api/admin/shipping/chowdeck-control/history` | Paginated immutable mode history |

All pages are zero-based, default to 20 records, require `size` from 1 through 100 and sort by descending immutable ID so PostgreSQL can use each table's primary-key index. Order details return at most the latest 100 payment attempts, payment receipts and status-history entries per collection. Waybill details return at most the latest 100 quotes, attempts, receipts, refunds, invitations and history entries per collection. Resource read transactions have five-second timeouts; the repeatable-read dashboard transaction has a ten-second timeout and its aggregate is cached in-process for 30 seconds.

Waybill timestamps are UTC `LocalDateTime` strings without an offset suffix. Dashboard `asOf` remains an `Instant` with an offset.

## Filters

`GET /api/admin/users`:

- `role=USER|ADMIN`
- `verified=true|false`
- `email=<exact normalized email>`

`GET /api/admin/orders`:

- `creatorId`
- `status`
- `paymentStatus`
- `settlementStatus`
- `fundingMode`

`GET /api/admin/disputes`:

- `orderId`
- `status=OPEN|UNDER_REVIEW|RESOLVED|REJECTED`

`GET /api/admin/waybills`:

- `creatorId`
- `status`
- `paymentStatus`

`GET /api/admin/withdrawals`:

- `userId`
- `status`

`GET /api/admin/settlements`:

- `orderId`
- `status`
- `settlementType`

`GET /api/admin/shipments`:

- `orderId`
- `status`
- `provider`

`GET /api/admin/wallet-transactions`:

- `userId`
- `orderId`
- `transactionType`

`GET /api/admin/payment-attempts`:

- `orderId`
- `status`

`GET /api/admin/payment-receipts`:

- `orderId`
- `classification`
- `providerStatus`

Filters on one request are combined with AND. Omit unused parameters rather than sending empty strings. Enum values are uppercase and case-sensitive. Invalid `page`/`size`, a malformed numeric ID, or an invalid enum returns `400`; a well-formed numeric detail ID with no record returns `404`. A valid non-admin JWT returns `403`, while missing or invalid authentication can return `401` or `403` from Spring Security.

## V20 Order Disputes

List disputes newest first:

```http
GET /api/admin/disputes?orderId=42&status=OPEN&page=0&size=20
Authorization: Bearer <admin-jwt>
```

```json
{
  "content": [
    {
      "id": 7,
      "orderId": 42,
      "status": "OPEN",
      "orderStatus": "IN_TRANSIT",
      "paymentStatus": "CONFIRMED",
      "settlementStatus": "PENDING",
      "reasonPreview": "The package arrived with visible screen damage.",
      "createdAt": "2026-08-22T16:30:00Z",
      "updatedAt": "2026-08-22T16:30:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

Read one dispute:

```http
GET /api/admin/disputes/7
Authorization: Bearer <admin-jwt>
```

```json
{
  "summary": {
    "id": 7,
    "orderId": 42,
    "status": "OPEN",
    "orderStatus": "IN_TRANSIT",
    "paymentStatus": "CONFIRMED",
    "settlementStatus": "PENDING",
    "reasonPreview": "The package arrived with visible screen damage.",
    "createdAt": "2026-08-22T16:30:00Z",
    "updatedAt": "2026-08-22T16:30:00Z"
  },
  "itemDescription": "MacBook Air M3",
  "reason": "The package arrived with visible screen damage.",
  "buyer": {
    "firstName": "Ada",
    "lastName": "Buyer",
    "maskedEmail": "a***@example.com",
    "maskedPhoneNumber": "******5678"
  },
  "vendor": {
    "firstName": "Bola",
    "lastName": "Vendor",
    "maskedEmail": "b***@example.com",
    "maskedPhoneNumber": "******4321"
  },
  "notifications": [
    {
      "audience": "BUYER",
      "status": "SENT",
      "attemptCount": 0,
      "nextAttemptAt": null,
      "sentAt": "2026-08-22T16:30:02Z",
      "failureReason": null
    }
  ]
}
```

The list never returns the full reason. Detail contact values stay masked consistently with other admin order views. Notification `status` is `PENDING`, `SENT`, or `FAILED`; each row is independent. `SENT` means Resend accepted the send request, not guaranteed mailbox delivery.

## V14 Waybill Operations

V14 waybills are a separate delivery-only domain. They do not reuse escrow `orders` and have no wallet, DVA, escrow, item principal, settlement, payout, withdrawal or buyer delivery-confirmation operation. Paystack Checkout collects only the frozen delivery total, and Terminal Africa delivery completes the waybill. The admin API is observational: it does not refund, cancel, retry, reclassify or resolve manual review.

### List Waybills

```http
GET /api/admin/waybills?creatorId=17&status=REFUND_PENDING&paymentStatus=REFUND_PENDING&page=0&size=20
Authorization: Bearer <admin-jwt>
```

All three filters are optional. The exact response type is `PageResponse<AdminWaybillSummaryResponse>`:

```json
{
  "content": [
    {
      "id": 73,
      "publicId": "c6c39335-d5c8-462e-b24f-34819c960c31",
      "creatorId": 17,
      "creatorName": "Ada Okafor",
      "maskedCreatorEmail": "a***@example.com",
      "maskedCreatorPhoneNumber": "******5678",
      "creatorRole": "SENDER",
      "payerType": "INVITED_PAYER",
      "description": "Two sealed document boxes",
      "declaredValue": 25000.00,
      "currency": "NGN",
      "status": "REFUND_PENDING",
      "paymentStatus": "REFUND_PENDING",
      "providerDeliveryFee": 15000.00,
      "deliveryMarkup": 1000.00,
      "deliveryFee": 16000.00,
      "serviceFee": 500.00,
      "totalAmount": 16500.00,
      "paymentDueAt": "2026-08-15T10:15:00",
      "fundingPaymentReference": "waybill-c6c39335d5c8462eb24f34819c960c31-ab12cd34ef56ab78",
      "fundedAt": "2026-08-15T10:08:00",
      "completedAt": null,
      "cancellationReason": "Delivery no longer required",
      "cancellationRequestedAt": "2026-08-15T11:00:00",
      "createdAt": "2026-08-15T10:00:00",
      "updatedAt": "2026-08-15T11:02:00"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

`description` is truncated to at most 120 characters in the summary. Pricing fields and payment/cancellation timestamps are nullable until their workflow stage. `providerDeliveryFee` is Terminal's raw rate and `deliveryMarkup` is ShopSecure's markup; creator/public DTOs intentionally do not expose either value.

### Waybill Detail

`{waybillId}` is the numeric database `id` returned by the list, not the public UUID.

```http
GET /api/admin/waybills/73
Authorization: Bearer <admin-jwt>
```

Representative exact `AdminWaybillDetailResponse` field shape:

```json
{
  "waybill": {
    "id": 73,
    "publicId": "c6c39335-d5c8-462e-b24f-34819c960c31",
    "creatorId": 17,
    "creatorName": "Ada Okafor",
    "maskedCreatorEmail": "a***@example.com",
    "maskedCreatorPhoneNumber": "******5678",
    "creatorRole": "SENDER",
    "payerType": "INVITED_PAYER",
    "description": "Two sealed document boxes",
    "declaredValue": 25000.00,
    "currency": "NGN",
    "status": "REFUND_PENDING",
    "paymentStatus": "REFUND_PENDING",
    "providerDeliveryFee": 15000.00,
    "deliveryMarkup": 1000.00,
    "deliveryFee": 16000.00,
    "serviceFee": 500.00,
    "totalAmount": 16500.00,
    "paymentDueAt": "2026-08-15T10:15:00",
    "fundingPaymentReference": "waybill-c6c39335d5c8462eb24f34819c960c31-ab12cd34ef56ab78",
    "fundedAt": "2026-08-15T10:08:00",
    "completedAt": null,
    "cancellationReason": "Delivery no longer required",
    "cancellationRequestedAt": "2026-08-15T11:00:00",
    "createdAt": "2026-08-15T10:00:00",
    "updatedAt": "2026-08-15T11:02:00"
  },
  "creator": {
    "id": 17,
    "firstName": "Ada",
    "lastName": "Okafor",
    "maskedEmail": "a***@example.com",
    "maskedPhoneNumber": "******5678",
    "enabled": true,
    "emailVerifiedAt": "2026-08-01T09:00:00",
    "createdAt": "2026-08-01T08:55:00"
  },
  "parties": [
    {
      "id": 101,
      "userId": 17,
      "role": "SENDER",
      "source": "ACCOUNT_SNAPSHOT",
      "firstName": "Ada",
      "lastName": "Okafor",
      "maskedEmail": "a***@example.com",
      "maskedPhoneNumber": "******5678",
      "city": "Lagos",
      "state": "Lagos",
      "country": "NG",
      "detailsCompletedAt": "2026-08-15T10:00:00",
      "contactVerifiedAt": "2026-08-15T10:00:00",
      "createdAt": "2026-08-15T10:00:00",
      "updatedAt": "2026-08-15T10:00:00"
    },
    {
      "id": 102,
      "userId": null,
      "role": "RECIPIENT",
      "source": "CREATOR_PROVIDED",
      "firstName": "Tobi",
      "lastName": "Adebayo",
      "maskedEmail": "t***@example.com",
      "maskedPhoneNumber": "******2222",
      "city": "Abuja",
      "state": "FCT",
      "country": "NG",
      "detailsCompletedAt": "2026-08-15T10:00:00",
      "contactVerifiedAt": "2026-08-15T10:03:00",
      "createdAt": "2026-08-15T10:00:00",
      "updatedAt": "2026-08-15T10:03:00"
    }
  ],
  "parcel": {
    "id": 88,
    "weightKg": 2.500,
    "lengthCm": 40.00,
    "widthCm": 30.00,
    "heightCm": 15.00,
    "quantity": 1,
    "parcelType": "parcel",
    "createdAt": "2026-08-15T10:00:00",
    "updatedAt": "2026-08-15T10:00:00"
  },
  "shipment": {
    "id": 45,
    "provider": "TERMINAL_AFRICA",
    "providerShipmentId": "SH-12345",
    "status": "CANCELLED",
    "trackingReference": "TRK-12345",
    "bookingAttempt": 1,
    "nextBookingAttemptAt": null,
    "failureReason": null,
    "cancellationAttempt": 2,
    "nextCancellationAttemptAt": null,
    "cancellationFailureReason": null,
    "providerUpdatedAt": "2026-08-15T11:02:00",
    "createdAt": "2026-08-15T10:04:00",
    "updatedAt": "2026-08-15T11:02:00"
  },
  "quotes": [
    {
      "id": 44,
      "carrierName": "DHL Express",
      "serviceDescription": "Express delivery",
      "providerAmount": 15000.00,
      "deliveryMarkup": 1000.00,
      "deliveryFee": 16000.00,
      "serviceFee": 500.00,
      "totalAmount": 16500.00,
      "currency": "NGN",
      "pickupEstimate": "Within 24 hours",
      "deliveryEstimate": "Within 3 days",
      "status": "USED",
      "selectedAt": "2026-08-15T10:05:00",
      "expiresAt": "2026-08-15T10:15:00",
      "createdAt": "2026-08-15T10:04:00"
    }
  ],
  "paymentAttempts": [
    {
      "id": 72,
      "invitationId": 91,
      "payerType": "INVITED_PAYER",
      "provider": "PAYSTACK",
      "reference": "waybill-c6c39335d5c8462eb24f34819c960c31-ab12cd34ef56ab78",
      "maskedCustomerEmail": "t***@example.com",
      "amount": 16500.00,
      "currency": "NGN",
      "status": "SUCCESS",
      "providerTransactionId": "845001",
      "nextRecheckAt": null,
      "failureReason": null,
      "createdAt": "2026-08-15T10:05:00",
      "updatedAt": "2026-08-15T10:08:00",
      "completedAt": "2026-08-15T10:08:00"
    }
  ],
  "paymentReceipts": [
    {
      "id": 39,
      "paymentAttemptId": 72,
      "providerEventKey": "845001",
      "reference": "waybill-c6c39335d5c8462eb24f34819c960c31-ab12cd34ef56ab78",
      "amount": 16500.00,
      "currency": "NGN",
      "channel": "card",
      "providerStatus": "SUCCESS",
      "classification": "MATCHED",
      "occurredAt": "2026-08-15T10:08:00",
      "createdAt": "2026-08-15T10:08:01"
    }
  ],
  "refunds": [
    {
      "id": 18,
      "paymentAttemptId": 72,
      "providerRefundId": null,
      "providerRefundReference": null,
      "amount": 16500.00,
      "currency": "NGN",
      "status": "REQUESTED",
      "reason": "Delivery no longer required",
      "failureReason": null,
      "providerCreatedAt": null,
      "providerUpdatedAt": null,
      "createdAt": "2026-08-15T11:02:00",
      "updatedAt": "2026-08-15T11:02:00",
      "completedAt": null
    }
  ],
  "invitations": [
    {
      "id": 91,
      "kind": "PAYMENT_ONLY",
      "targetPartyRole": null,
      "canPay": false,
      "stagedFirstName": "Tobi",
      "stagedLastName": "Adebayo",
      "maskedStagedEmail": "t***@example.com",
      "maskedStagedPhoneNumber": "******2222",
      "otpSendCount": 1,
      "otpFailedAttemptCount": 0,
      "otpLockedAt": null,
      "expiresAt": "2026-08-15T10:15:00",
      "verifiedAt": "2026-08-15T10:03:00",
      "detailsCompletedAt": "2026-08-15T10:00:00",
      "paymentCompletedAt": "2026-08-15T10:08:00",
      "revokedAt": "2026-08-15T11:00:00",
      "createdAt": "2026-08-15T10:00:00",
      "updatedAt": "2026-08-15T11:00:00"
    }
  ],
  "history": [
    {
      "fromStatus": "CANCELLATION_PENDING",
      "toStatus": "REFUND_PENDING",
      "reason": "Waybill payment refund queued",
      "createdAt": "2026-08-15T11:02:00"
    }
  ]
}
```

The nested `waybill` object is the same summary DTO used by the list. Parties are ordered by role ascending. `quotes`, `paymentAttempts`, `paymentReceipts`, `refunds`, `invitations` and `history` are newest first and capped independently at 100. `parcel` and `shipment` can be `null`; all collections are arrays, including when empty.

### Operational Interpretation

- `SENDER` is always pickup and `RECIPIENT` is always delivery. `creatorRole` identifies which party owns the account. Admin `payerType` is derived as `CREATOR` when the selected payer role equals the creator role and `INVITED_PAYER` otherwise.
- Current waybills persist both parties at creation. Current invitations are `PAYMENT_ONLY`, are issued only after creator quote selection, and stage the already-recorded non-creator payer contact. `PARTY_COMPLETION`, `INVITEE_PROVIDED`, and `AWAITING_PARTY_DETAILS` are historical values only.
- `totalAmount = providerDeliveryFee + deliveryMarkup + serviceFee`. `deliveryFee = providerDeliveryFee + deliveryMarkup`; `declaredValue` is excluded.
- Quote and payment expiry is `paymentDueAt`, copied from the selected quote's `expiresAt`. Expired open attempts can become `SUPERSEDED`; later capture is classified `LATE` and fully refunded.
- Payment receipt classifications are `MATCHED`, `UNDERPAID`, `OVERPAID`, `DUPLICATE`, `LATE` and `FAILED`. Non-matched captured amounts are never partially applied; safe captures are fully refunded.
- Shipment booking, payment verification, carrier cancellation and refunds are scheduler-reconciled from persisted records. `bookingAttempt`/`nextBookingAttemptAt` and `cancellationAttempt`/`nextCancellationAttemptAt` show durable backoff state.
- A carrier `DELIVERED` status completes a waybill automatically. There is no user confirmation or settlement phase.
- `CANCELLATION_PENDING` remains nonterminal while a `DRAFT`, `PENDING` or `CONFIRMED` shipment is retried. Retry exhaustion, unsafe carrier state, payment ambiguity or refund uncertainty moves the root to `MANUAL_REVIEW`.
- Root refund success produces `REFUNDED/REFUNDED`. Refund failure, needs-attention or an unresolved dispatch outcome produces `MANUAL_REVIEW/REVIEW`. A duplicate-payment refund can complete without changing an already-funded root waybill.

Root states are `AWAITING_PARTY_DETAILS`, `AWAITING_QUOTE`, `AWAITING_PAYMENT`, `PAYMENT_REVIEW`, `SHIPMENT_BOOKING`, `READY_FOR_DELIVERY`, `SHIPPING_FAILED`, `CANCELLATION_PENDING`, `REFUND_PENDING`, `COMPLETED`, `CANCELLED`, `REFUNDED`, and `MANUAL_REVIEW`. Payment states are `NOT_STARTED`, `PENDING`, `CONFIRMED`, `REFUND_PENDING`, `REFUNDED`, and `REVIEW`; attempt states are `INITIALIZING`, `INITIALIZATION_UNKNOWN`, `INITIALIZED`, `SUCCESS`, `FAILED`, `ABANDONED`, and `SUPERSEDED`. Refund states are `REQUESTED`, `DISPATCHING`, `DISPATCH_UNKNOWN`, `PENDING`, `PROCESSING`, `PROCESSED`, `FAILED`, and `MANUAL_REVIEW`. Quote states are `AVAILABLE`, `SELECTED`, `USED`, and `EXPIRED`; shipment states are `DRAFT`, `CONFIRMED`, `PENDING`, `IN_TRANSIT`, `DELIVERED`, `CANCELLED`, `FAILED`, and `UNKNOWN`.

### Waybill Privacy

Admin data is masked but remains sensitive. Party street lines and postal codes, carrier logos and shipment tracking URLs are omitted. The response also omits invitation token/access hashes and OTP challenge IDs; checkout authorization URLs, access codes and idempotency keys; waybill creation/cancellation idempotency keys and request hashes; provider rate/internal IDs; and every raw provider secret. Names, operational references, failure reasons, city/state/country, OTP counters and timestamps remain visible to admins and must be excluded from analytics, session replay and browser persistence.

## Chowdeck Shipping Control

`GET /api/admin/shipping/chowdeck-control` returns:

```json
{
  "configuredMode": "OFF",
  "effectiveMode": "OFF",
  "hardKillActive": true,
  "version": 0,
  "updatedByAdminId": null,
  "updatedAt": "2026-08-19T18:00:00Z"
}
```

Modes are `OFF`, `SHADOW`, and `ON`. `OFF` makes no Chowdeck calls for new shipments. `SHADOW` observes eligible Relay fees but still returns Terminal quotes. `ON` permits real Relay quotes and bookings for eligible same-state routes. `CHOWDECK_HARD_DISABLED=true` forces `effectiveMode` to `OFF`, even if an administrator configured `ON`. Existing shipments always retain their persisted provider and are not migrated when this mode changes.

Mode changes apply to quote decisions that begin after the control transaction commits. An already-running quote request may drain under the mode/version it already read; set the environment hard kill and restart/redeploy when provider calls must stop at the process boundary.

`PUT /api/admin/shipping/chowdeck-control` requires:

```json
{
  "mode": "ON",
  "expectedVersion": 0,
  "reason": "Approved same-state rollout"
}
```

`reason` is required and limited to 500 characters. `expectedVersion` prevents two administrators from silently overwriting each other. A stale value returns `409` with `Chowdeck control changed; refresh and retry`. A successful state change increments `version`, records the authenticated admin, and appends immutable history. Repeating the current mode is idempotent and does not create another history row.

`GET /api/admin/shipping/chowdeck-control/history?page=0&size=20` returns a standard newest-first `PageResponse` whose rows contain `id`, `fromMode`, `toMode`, `reason`, `changedByAdminId`, `changedAt`, and `controlVersion`. Page size must be 1 through 100. No control route exposes the Chowdeck key, provider request/response body, or wallet balance.

## Dashboard semantics

Dashboard queries do not include V14 waybill rows. Waybill counts, delivery values, receipts, refunds, classifications and shipment states must be inspected through `/api/admin/waybills` and `/api/admin/waybills/{waybillId}`.

- Confirmed order value includes only orders whose payment status is `CONFIRMED`.
- Available wallet balance is the current spendable balance.
- Outstanding withdrawal amount includes `PENDING`, `DISPATCH_UNKNOWN` and `REQUIRES_ACTION` withdrawals.
- Customer obligation is available wallet balance plus outstanding withdrawals.
- Unsettled order principal is the item amount on confirmed orders whose settlement is not complete.
- Payment receipts requiring review report successful `UNDERPAID`, `OVERPAID`, `LATE`, `DUPLICATE` and `UNMATCHED` receipt amounts by classification and currency. These are gross operational review amounts, not an accounting liability total.
- `disputesByStatus` counts persisted order disputes by case status; `openDisputes` is the `OPEN` count used by the admin attention queue.
- Fee totals are operational totals and should not be presented as recognized accounting revenue without reconciliation.

## Provisioning an admin

Admin creation is intentionally not exposed over HTTP. Promote a verified account through a controlled database operation and invalidate its existing JWTs:

```sql
BEGIN;

UPDATE users
SET role = 'ADMIN',
    auth_version = auth_version + 1
WHERE lower(email) = lower('<verified-admin-email>')
  AND email_verified_at IS NOT NULL;

COMMIT;
```

The administrator must log in again after promotion. Every administrator should use an individual account.

## Mutations

Except for the audited Chowdeck mode control above, this release contains no wallet adjustment, status override, refund, payout retry, shipment retry, waybill cancellation/retry/reclassification/manual-review resolution, role-edit or account-deletion endpoint. Those actions require durable admin audit records and additional workflow concurrency hardening before they are safe for live customer funds.
