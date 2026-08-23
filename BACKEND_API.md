# ShopSecure Backend API

This document describes the implemented backend contract. Provider secret keys must never be sent to a browser or mobile client.

## V20 Order Disputes

Flyway V20 adds one notification-only dispute record per funded escrow order and a durable, independent email outbox for the buyer, vendor, and ShopSecure operations. Creating a dispute never changes order, payment, shipment, settlement, payout, or refund state. Shipping and settlement continue under the order's existing rules.

The public intake endpoint accepts a canonical positive numeric order ID and requires no JWT, guest token, tracking token, or OTP:

```http
POST /orders/{orderId}/disputes
Content-Type: application/json

{
  "reason": "The package arrived with visible screen damage."
}
```

`reason` is required, may contain ordinary line breaks and tabs, and is limited to 2000 characters. Other control characters are rejected. Requests larger than 16 KiB receive `413`. A syntactically valid request always receives the same non-cacheable `202` response whether the order is unknown, unfunded, missing a counterparty, already disputed, or accepted:

```json
{
  "message": "If the order is eligible, the dispute has been received and the ShopSecure team will contact both parties."
}
```

This intentionally prevents the response from confirming whether a sequential order ID exists. Only an order with `paymentStatus: "CONFIRMED"`, a non-null `fundedAt`, a complete counterparty, and no existing dispute creates data. The order row is locked during intake and `order_disputes.order_id` is unique, so concurrent and repeated submissions cannot create a second case or resend the three opening emails. Malformed IDs/bodies return `400`; endpoint-specific per-IP and per-order throttles return `429` with `Retry-After`.

Accepted disputes start as `OPEN`. The persisted enum also reserves `UNDER_REVIEW`, `RESOLVED`, and `REJECTED`, but V20 exposes no public or admin status mutation. Each opening email safely escapes the item description and reason, states that the ShopSecure team will contact both parties, and states that the dispute does not automatically cancel delivery, reverse settlement, or issue a refund. Buyer, vendor, and the fixed operations recipient `fizzois580@gmail.com` have separate `PENDING | SENT | FAILED` rows, retry state, and stable Resend keys in the form `order-dispute-opened/{notificationId}`. One destination failing does not block the other two.

Admin reads are available at:

```text
GET /api/admin/disputes?orderId={orderId}&status=OPEN&page=0&size=20
GET /api/admin/disputes/{disputeId}
```

The list is newest-ID-first and returns a bounded single-line reason preview. Detail returns the full reason, item description, masked buyer/vendor contact snapshots, and all three notification delivery states. The dashboard adds `operations.disputesByStatus` and `operations.openDisputes`. All admin routes remain read-only and require `ROLE_ADMIN`.

## V19 Carrier-Pickup Escrow Release

Flyway V19 changes the escrow release policy for orders created after deployment. New orders persist `escrowReleasePolicy: "CARRIER_PICKUP"`; existing orders are migrated as `BUYER_DELIVERY_CONFIRMATION` and keep their previous buyer-confirmation workflow. This prevents an upgrade from unexpectedly releasing escrow held by an existing order.

For a new order, authenticated carrier evidence normalized to shipment `IN_TRANSIT` confirms physical pickup. `CONFIRMED` means pickup is only booked and never releases money. `DELIVERED` is a catch-up trigger if the `IN_TRANSIT` update was missed. Terminal updates arrive through the signed webhook. Chowdeck's unsigned callback is only a reconciliation signal: the backend fetches the delivery through Chowdeck's authenticated API before applying status or releasing funds, with persisted-provider polling retained as fallback.

The first pickup observation atomically records `carrierPickupConfirmedAt`, creates one `EXTERNAL_PAYOUT` settlement for item amount only, and appends durable buyer and vendor pickup-email intents. Paystack dispatch runs after that carrier transaction commits and uses the deterministic reference `shopsecure_order_{orderId}_payout`; scheduled payout reconciliation recovers an interrupted dispatch. Replayed, newer duplicate, or polling observations cannot create a second settlement or email intent. New orders never fall back to vendor wallet credit.

Payout success while the parcel is moving changes order status to `IN_TRANSIT`, with `settlementStatus: "COMPLETED"`; it does not claim delivery completion. A later carrier `DELIVERED` update changes the order to `COMPLETED`. Because an external bank payout cannot be automatically clawed back, a later carrier failure or cancellation requires manual financial review and does not silently refund the buyer.

## V18 Chowdeck Relay Control

Flyway V18 adds optional Chowdeck Relay shipping and one audited admin control. Customer order and waybill DTOs remain provider-neutral. A shipment stores its provider when the first quote draft is created, and all later quote refresh, booking, tracking, cancellation, reconciliation, and waybill-notification calls use that persisted provider. Changing the global control never moves an existing shipment between providers.

The database control starts at `OFF` and supports `OFF`, `SHADOW`, and `ON`. `OFF` makes no Chowdeck calls for new shipments. `SHADOW` requests an eligible Chowdeck fee for operational comparison, discards it, and continues with Terminal Africa. `ON` permits real Chowdeck Relay quotes and bookings. `CHOWDECK_HARD_DISABLED=true` overrides every configured mode to effective `OFF`; set it to `false` and provide `CHOWDECK_SECRET_KEY` before an administrator can make Chowdeck effective.

Chowdeck is considered only when pickup and delivery normalize to the same supported service area: Lagos, Ogun, Delta, or FCT/Abuja state-wide; Ibadan within Oyo; or Port Harcourt within Rivers. All interstate routes, including routes for which Chowdeck happens to return a fee, remain on Terminal Africa. If an eligible Chowdeck fee request fails before the draft is persisted, quote generation falls back to Terminal. A selected or paid Chowdeck shipment never silently changes carrier after a booking failure.

## V17 ShopSecure Order Tracking

Flyway V17 adds one stable ShopSecure tracking capability and two durable tracking-email intents to every escrow order when payment first becomes `CONFIRMED`. This applies to buyer-created wallet orders and vendor-created Paystack Checkout orders. The tracking capability is separate from the buyer payment link, guest access tokens, and Terminal's carrier URL.

The browser link is generated from `FRONTEND_BASE_URL`:

```text
https://<frontend-host>/orders/tracking/{trackingToken}
```

The frontend route reads the token and calls this public, read-only backend endpoint without a member JWT:

```text
GET /orders/tracking/{trackingToken}
```

```json
{
  "orderId": 42,
  "itemDescription": "MacBook Air M3",
  "itemAmount": 125000.00,
  "currency": "NGN",
  "creatorRole": "BUYER",
  "fundingMode": "WALLET",
  "orderStatus": "PAYOUT_PENDING",
  "trackingStatus": "IN_TRANSIT",
  "paymentStatus": "CONFIRMED",
  "buyer": {
    "role": "BUYER",
    "firstName": "Ada",
    "lastName": "Buyer",
    "email": "ada@example.com",
    "phoneNumber": "+2348098765432",
    "address": { "line1": "20 Admiralty Way", "line2": "", "city": "Lagos", "state": "Lagos", "country": "NG", "postalCode": "101233" }
  },
  "vendor": {
    "role": "VENDOR",
    "firstName": "Bola",
    "lastName": "Vendor",
    "email": "bola@example.com",
    "phoneNumber": "+2348012345678",
    "address": { "line1": "12 Marina Road", "line2": "Suite 4", "city": "Lagos", "state": "Lagos", "country": "NG", "postalCode": "100221" }
  },
  "vendorPayoutAccount": {
    "bankAccountLast4": "6789",
    "bankCode": "058",
    "bankName": "Guaranty Trust Bank",
    "bankAccountName": "Bola Vendor"
  },
  "pricing": { "itemAmount": 125000.00, "deliveryFee": 5750.00, "serviceFee": 500.00, "totalAmount": 131250.00, "currency": "NGN" },
  "parcel": { "weightKg": 2.000, "lengthCm": 35.00, "widthCm": 25.00, "heightCm": 12.00, "quantity": 1, "parcelType": "parcel" },
  "shipment": {
    "provider": "TERMINAL_AFRICA",
    "providerShipmentId": "SHP-123",
    "status": "IN_TRANSIT",
    "trackingReference": "TRK-123",
    "trackingUrl": "https://carrier.example/track/TRK-123",
    "selectedQuote": { "id": 9, "carrierName": "DHL", "deliveryEstimate": "3-5 business days" }
  },
  "escrow": {
    "releasePolicy": "CARRIER_PICKUP",
    "status": "PENDING",
    "settlementType": "EXTERNAL_PAYOUT",
    "trigger": "CARRIER_PICKUP",
    "amount": 125000.00,
    "amountPending": 125000.00,
    "amountReleased": 0.00,
    "currency": "NGN"
  },
  "fundedAt": "2026-08-19T09:07:47Z",
  "carrierPickupConfirmedAt": "2026-08-19T12:30:00Z",
  "trackingAvailableAt": "2026-08-19T09:07:47.071047Z",
  "trackingExpiresAt": "2027-02-15T09:07:47.071047Z",
  "updatedAt": "2026-08-19T12:30:00Z"
}
```

`trackingStatus` values are `BOOKING`, `AWAITING_PICKUP`, `IN_TRANSIT`, `DELIVERED`, `EXCEPTION`, and `CANCELLED`. This is intentionally a full bearer-link order view: no JWT, guest token, or OTP is required, and anyone holding the URL can read buyer/vendor names, phone numbers, email addresses, full order addresses, item/pricing/parcel data, shipment identifiers and URLs, and escrow pending/released amounts. The vendor bank account is limited to the stored bank name, account name, bank code, and last four digits; ShopSecure does not retain or return the full account number. Raw share/payment tokens, payout-recipient IDs, encryption fields, and provider credentials are never returned. The endpoint is served from persisted state and never calls a carrier.

`ShippingStatusResponse` adds nullable `shopSecureTrackingUrl` and `trackingAvailableAt`. Both are null before confirmed payment and carry the same stable values in authenticated creator responses and verified guest status responses after funding. The URL remains unchanged as signed Terminal webhooks update the stored shipment status.

The raw 256-bit tracking token is stored only as a SHA-256 lookup hash plus AES-GCM ciphertext, expires after `ORDER_TRACKING_TOKEN_TTL` (`P180D` by default), and is redacted from application logs. Invalid, expired, or revoked links return the same `404`. Responses set `Cache-Control: no-store`, `Pragma: no-cache`, `Referrer-Policy: no-referrer`, and `X-Robots-Tag: noindex, nofollow, noarchive`.

Payment confirmation also commits one durable email intent for the buyer and one for the vendor. Each email contains the same ShopSecure link and is dispatched after commit with independent retry state and a stable Resend idempotency key. Email failure never changes payment or shipping status. V17 does not send historical tracking emails to orders that were already funded before deployment.

## V16 Complete-At-Creation Contract

This section is authoritative for escrow orders and waybills. Any older example later in this document that mentions create-time share links, public party completion, public quote selection, `payerType` in a waybill client request, or `AWAITING_PARTY_DETAILS` for a new record is superseded by this section. Those enum values and service methods remain only for historical database rows; no current controller exposes the old onboarding routes.

Flyway V20 is the current schema version. V16 stores the resolved per-order vendor payout snapshot and closes unfunded legacy `AWAITING_COUNTERPARTY` drafts, V17 adds custom order tracking links and durable participant emails, V18 adds Chowdeck Relay plus its audited admin control, V19 adds per-order carrier-pickup escrow release, and V20 adds notification-only order disputes with durable three-recipient email delivery.

### Escrow Orders

The authenticated creator submits the opposite party, parcel, both delivery addresses, and vendor payout account in `POST /orders`. The creator's name, email, and phone are taken from the verified account snapshot; `creatorAddress` supplies that party's delivery address.

```json
{
  "amount": 125000.00,
  "itemDescription": "MacBook Air M3",
  "creatorRole": "BUYER",
  "creatorAddress": {
    "line1": "20 Admiralty Way",
    "line2": "Lekki Phase 1",
    "city": "Lagos",
    "state": "Lagos",
    "country": "NG",
    "postalCode": "101233"
  },
  "counterparty": {
    "firstName": "Bola",
    "lastName": "Vendor",
    "email": "vendor@example.com",
    "phoneNumber": "+2348098765432",
    "address": {
      "line1": "12 Marina Road",
      "line2": null,
      "city": "Lagos",
      "state": "Lagos",
      "country": "NG",
      "postalCode": "100221"
    }
  },
  "parcel": {
    "weightKg": 2.000,
    "lengthCm": 40.00,
    "widthCm": 30.00,
    "heightCm": 15.00
  },
  "vendorBankAccount": {
    "accountNumber": "0000000000",
    "bankCode": "001"
  }
}
```

`counterparty`, `parcel`, and `vendorBankAccount` are required for both creator roles. The backend resolves the bank account and creates the Paystack transfer recipient before persisting the order. Only the last four digits, resolved bank metadata, and provider recipient code are retained on the order. The initial status is `AWAITING_SHIPPING_RATE`.

`CreateOrderResponse` contains `id`, `itemAmount`, `currency`, `itemDescription`, `creatorRole`, `counterpartyRole`, `fundingMode`, `escrowReleasePolicy`, `status`, and `createdAt`. It contains no raw token or link.

The creator owns both shipping operations regardless of role:

```text
POST /orders/{orderId}/shipping/quotes
PUT  /orders/{orderId}/shipping/selection
```

`ShippingQuoteResponse` includes `expiresAt`. Selection freezes the full buyer total and returns `ShippingSelectionResponse` with `status`, `fundingMode`, `selectedQuote`, `pricing`, nullable `paymentInstructions`, and nullable `buyerPaymentLink`.

- Buyer creator: `fundingMode=WALLET`, `buyerPaymentLink=null`; the creator calls `POST /orders/{orderId}/payments/wallet`.
- Vendor creator: `fundingMode=PAYSTACK_CHECKOUT`; selection returns the payment-only buyer link.
- The buyer always pays item amount + delivery fee + service fee. The vendor never pays escrow delivery fees.
- The vendor receives only the item amount at settlement, through the payout recipient frozen at creation.
- Wallet debit and Paystack checkout reject an expired selected quote. An unfunded order with no initialized checkout can return to fresh rates; a captured or unresolved checkout moves to `MANUAL_REVIEW`. A capture after expiry is recorded as `LATE` and is not used to fund the order automatically.

The public order API exists only for a vendor-created order after creator quote selection:

```text
GET  /orders/token/{shareToken}
POST /orders/token/{shareToken}/payment-access-otp
POST /orders/token/{shareToken}/verify-otp
POST /orders/token/{shareToken}/resend-otp
POST /orders/token/{shareToken}/access-recovery-otp
POST /orders/token/{shareToken}/recover-access
POST /orders/token/{shareToken}/payments/checkout
GET  /orders/token/{shareToken}/status
POST /orders/token/{shareToken}/delivery-otp
POST /orders/token/{shareToken}/confirm-delivery
```

The buyer link verifies payment access only. These routes no longer exist:

```text
POST /orders/token/{shareToken}/bank-account/resolve
POST /orders/token/{shareToken}/counterparty-info
POST /orders/token/{shareToken}/shipping/quotes
PUT  /orders/token/{shareToken}/shipping/selection
```

Private order detail/list DTOs use nullable `buyerPaymentLink`, not `shareableLink`, and expose the frozen `vendorPayoutAccount` as `bankAccountLast4`, `bankAccountName`, and `bankName`. `GET /orders/seller/pending-amount` reports only securely account-linked seller orders; an unregistered counterparty is not auto-linked from creator-supplied contact data.

### Waybills

A waybill remains delivery-only and has no escrow item payment or vendor settlement. `POST /waybills` requires both delivery parties, the parcel, and the party role that pays the complete delivery total:

```json
{
  "creatorRole": "SENDER",
  "payerRole": "RECIPIENT",
  "description": "Two sealed document boxes",
  "declaredValue": 25000.00,
  "creatorAddress": {
    "line1": "1 Marina Road",
    "line2": null,
    "city": "Lagos",
    "state": "Lagos",
    "country": "NG",
    "postalCode": "100001"
  },
  "parcel": {
    "weightKg": 2.500,
    "lengthCm": 40.00,
    "widthCm": 30.00,
    "heightCm": 15.00
  },
  "oppositeParty": {
    "firstName": "Tobi",
    "lastName": "Adebayo",
    "email": "tobi@example.com",
    "phoneNumber": "+2348022222222",
    "address": {
      "line1": "2 Receiver Road",
      "line2": null,
      "city": "Abuja",
      "state": "FCT",
      "country": "NG",
      "postalCode": "900001"
    }
  }
}
```

`payerRole` is `SENDER` or `RECIPIENT`; it is independent of `creatorRole`. When both roles match, the creator pays. When they differ, the opposite party pays through a payment-only link. Internal/admin records still derive `payerType=CREATOR|INVITED_PAYER`, but client request and normal response DTOs use `payerRole`.

Every new waybill starts in `AWAITING_QUOTE`, with both parties persisted and no link. `CreateWaybillResponse` contains `details`, nullable `paymentLink`, nullable `expiresAt`, and `replayed`; both link fields are null at creation.

The authenticated creator always requests and selects quotes:

```text
POST /waybills/{publicId}/shipping/quotes
PUT  /waybills/{publicId}/shipping/selection
```

Selection returns `WaybillQuoteSelectionResponse`:

```json
{
  "selectedQuote": {},
  "paymentLink": "https://shopsecure.app/waybills/invitations/<token>",
  "paymentLinkExpiresAt": "2026-08-18T10:15:00"
}
```

The link fields are null when the creator pays. For an opposite-party payer, the payment link expires no later than the selected quote/payment deadline. Reissue revokes the old link and access token, marks an open checkout `SUPERSEDED`, and returns `paymentLink` plus `expiresAt`; any later capture against the old attempt follows the `LATE` refund path.

Current public waybill routes are payment-only:

```text
GET  /waybills/invitations/{invitationToken}
POST /waybills/invitations/{invitationToken}/payment-access-otp
POST /waybills/invitations/{invitationToken}/verify-otp
POST /waybills/invitations/{invitationToken}/resend-otp
POST /waybills/invitations/{invitationToken}/access-recovery
POST /waybills/invitations/{invitationToken}/access-recovery/verify
GET  /waybills/invitations/{invitationToken}/status
POST /waybills/invitations/{invitationToken}/payments/checkout
```

There are no public party-details, payer-claim, quote-request, or quote-selection routes. Public responses use `invitationKind=PAYMENT_ONLY`, `targetPartyRole=payerRole`, `detailsRequired=false`, and `payerRole`; verified status returns both masked parties.

All public order/waybill token responses and creator selection responses set `Cache-Control: no-store`, `Pragma: no-cache`, and `Referrer-Policy: no-referrer`.

> The remaining sections document unchanged infrastructure and historical implementation detail. Where they conflict with the V16 section above, the V16 contract is the callable API.

## Postman

Import `postman/ShopSecure V19 Carrier Pickup Settlement and Shipping Control.postman_collection.json` with `postman/ShopSecure Local.postman_environment.json` for the current complete-at-creation, automatic pickup-settlement, custom-tracking, and Chowdeck-control workflows. The older backend collection remains a reference for unchanged authentication, wallet, admin, and signed local/test webhook utilities; its V15 onboarding folders are not callable in V19. See `postman/README.md` before running provider callbacks.

## Pricing

The backend calculates all monetary values. Clients select a local shipping quote ID and never submit a total.

```text
customer delivery fee = persisted provider rate + SHIPPING_DELIVERY_MARKUP
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

Wallet-payment and legacy delivery-confirmation mutations also require:

```http
Idempotency-Key: <unique-client-generated-value>
```

Guest tokens are stored as SHA-256 hashes, expire after `ORDER_GUEST_TOKEN_TTL`, and can be recovered through a new SMS/email OTP while the order is nonterminal.

## Order Roles

- Registered accounts have role `USER`.
- `creatorRole` is selected per order as `BUYER` or `VENDOR`.
- Both parties, addresses, parcel details, and the vendor payout account are complete at creation.
- The vendor is always the shipment pickup party.
- The buyer is always the shipment delivery party and pays the complete escrow total.
- The authenticated creator always requests and selects the shipping quote.
- A public link is issued only for the non-creator buyer of a vendor-created order and accepts payment access only.
- New buyer-created orders use `fundingMode: WALLET`; the registered buyer pays explicitly from their ShopSecure wallet.
- New vendor-created orders use `fundingMode: PAYSTACK_CHECKOUT`; the verified guest buyer opens an order-specific Paystack Checkout session.
- Every registered user keeps a personal DVA for wallet top-ups. Checkout does not replace virtual accounts.

## Addresses

Shipping addresses require country code `NG` and a postal code:

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

### Public Location Catalogue

The frontend can populate Nigerian state and city selectors without calling Terminal Africa directly:

| Method | Endpoint | Response |
|---|---|---|
| `GET` | `/api/locations/states` | `ShippingStateResponse[]` |
| `GET` | `/api/locations/states/{stateCode}/cities` | `ShippingCityResponse[]` |

Both routes are public and return `Cache-Control: max-age=3600, public`. State response example:

```json
[
  {
    "code": "LA",
    "name": "Lagos"
  }
]
```

City response example:

```json
[
  {
    "name": "Ikeja",
    "stateCode": "LA"
  }
]
```

Use `code` only to request the corresponding city list. Existing address payloads continue to submit the selected state `name` in `state`, the selected city `name` in `city`, and `NG` in `country`. State codes are case-insensitive; blank, malformed, and unsupported state codes return `400`.

The backend loads one normalized Nigeria-wide catalogue through the active shipping provider, shares it across all requests in that application instance, and starts an asynchronous refresh after 24 hours. A failed refresh retains the last successful catalogue. If no catalogue has ever loaded, requests return `503`; that failure is cached for one minute before another provider attempt. Local/test profiles return deterministic stub locations; production uses authenticated Terminal Africa calls. Provider credentials and raw provider payloads are never returned.

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
| `GET` | `/orders?page=0&size=20` | Paginated creator orders |
| `GET` | `/orders/{orderId}` | Private creator view |
| `GET` | `/orders/{orderId}/history` | Status history |
| `POST` | `/orders/{orderId}/shipping/quotes` | Order creator requests quotes |
| `PUT` | `/orders/{orderId}/shipping/selection` | Order creator selects a local quote ID |
| `POST` | `/orders/{orderId}/payments/wallet` | Buyer creator explicitly funds a wallet-mode order; requires `Idempotency-Key` |
| `GET` | `/orders/{orderId}/shipping/tracking` | Refresh provider tracking |
| `POST` | `/orders/{orderId}/shipping/cancel` | Cancel an eligible unpaid order |
| `POST` | `/orders/{orderId}/confirm-delivery` | Legacy-policy buyer creator confirms with `{"confirmation":true}` and initiates vendor payout |
| `POST` | `/api/bank-accounts/resolve` | Resolve an account for an authenticated member |
| `GET` | `/api/wallet` | Current wallet |
| `GET` | `/api/wallet/transactions?page=0&size=20` | Paginated wallet ledger |
| `GET` | `/api/wallet/withdrawals?page=0&size=20` | Paginated wallet withdrawal history |
| `GET` | `/orders/seller/pending-amount` | Account-linked seller pending amount summary |

Every create-order request requires `counterparty`, `parcel`, and `vendorBankAccount`.

Paginated endpoints use zero-based `page`, default `size=20`, and maximum `size=100`. Responses contain `content`, `page`, `size`, `totalElements`, `totalPages`, `first`, and `last`.

### Buyer Payment Links

Authenticated creator responses from `GET /orders` and `GET /orders/{orderId}` include nullable `buyerPaymentLink`. It is present only for a vendor-created checkout order after quote selection. Creation responses never contain a raw token or link.

```json
{
  "id": 42,
  "buyerPaymentLink": "https://shopsecure.app/orders/token/<share-token>",
  "status": "AWAITING_PAYMENT"
}
```

`FRONTEND_BASE_URL` controls the link origin. Treat the complete link as sensitive: do not write it to logs, analytics, or error reports.

## Public And Guest Endpoints

| Method | Endpoint | Access |
|---|---|---|
| `GET` | `/orders/tracking/{trackingToken}` | Public full bearer-link order, shipment, and escrow snapshot; no OTP |
| `GET` | `/orders/token/{shareToken}` | Public safe order summary |
| `GET` | `/api/banks` | Public Paystack-supported Nigerian banks |
| `GET` | `/api/locations/states` | Public Nigerian shipping states |
| `GET` | `/api/locations/states/{stateCode}/cities` | Public shipping cities for a returned state code |
| `POST` | `/orders/{orderId}/disputes` | Public generic reason-only dispute intake for a canonical numeric order ID |
| `POST` | `/orders/token/{shareToken}/payment-access-otp` | Public buyer payment-access OTP |
| `POST` | `/orders/token/{shareToken}/verify-otp` | Public; returns guest access token |
| `POST` | `/orders/token/{shareToken}/resend-otp` | Public |
| `POST` | `/orders/token/{shareToken}/access-recovery-otp` | Public; sends recovery OTP |
| `POST` | `/orders/token/{shareToken}/recover-access` | Public; rotates guest token |
| `POST` | `/orders/token/{shareToken}/payments/checkout` | Verified guest buyer; initialize or replay the active Paystack Checkout session |
| `GET` | `/orders/token/{shareToken}/status` | Verified guest |
| `POST` | `/orders/token/{shareToken}/delivery-otp` | Verified guest buyer on a legacy-policy order |
| `POST` | `/orders/token/{shareToken}/confirm-delivery` | Verified guest buyer on a legacy-policy order |

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
- Checkout and wallet funding reject an expired selected quote. An on-time payment is determined from the provider occurrence timestamp; a capture after expiry is classified `LATE` and held for manual review.
- A second success after funding is recorded as `DUPLICATE` for refund review and never books shipping twice.
- The order can be cancelled after quote selection but before checkout initialization. Once any checkout URL has been issued, cancellation requires manual review because a late payment remains possible.
- Standard Checkout collects into ShopSecure's Paystack integration. It does not credit the seller's ShopSecure wallet or release seller funds at payment time.

Migrated `DIRECT_DVA` orders retain the old cumulative-transfer behavior so already-issued bank instructions and delayed transfers are not silently reclassified. No new order is created in this mode.

## Shipping

The Terminal adapter uses:

```text
GET  /states?country_code=NG
GET  /cities?country_code=NG
POST /addresses/validate
POST /shipments/quick
GET  /rates/shipment
GET  /users/wallet-balance
POST /shipments/pickup
GET  /shipments/track/{shipmentId}
POST /shipments/cancel
```

The Chowdeck Relay adapter uses only `https://api.chowdeck.com`:

```text
POST /relay/delivery/fee
POST /relay/delivery
GET  /relay/delivery/{reference}
POST /relay/delivery/{reference}/cancel
GET  /relay/wallet/balance
```

Chowdeck fee and wallet values are converted from kobo to NGN. Its fee ID is the persisted provider rate ID, and its locally enforced quote lifetime defaults to five minutes. The deterministic ShopSecure reference (`order-{id}` or `waybill-{publicId}`) is also the Relay delivery reference. A duplicate or ambiguous create response is reconciled by that reference before a later retry. Chowdeck callbacks trigger the same authenticated tracking reconciliation immediately; scheduled refresh remains the recovery path for missing callbacks or provider outages.

After wallet funding, confirmed checkout, or exact legacy direct payment, the backend resolves the provider from the persisted shipment and checks that provider's wallet liquidity. Terminal drafts are tracked before every booking attempt so an already-active shipment is accepted instead of being booked twice. Chowdeck does not create a remote draft during quoting, so its first remote delivery creation occurs only after payment. Retries use persisted backoff, bounded batches, and a configurable attempt limit; exhausted or provider-terminal shipments become `MANUAL_REVIEW`. Drop-off-only Terminal rates are not offered. For V19 orders, carrier `IN_TRANSIT` releases seller funds; `DELIVERED` is used only as missed-pickup recovery and fulfillment completion.

## Settlement

New buyer-created and vendor-created orders use the same release path:

```text
authenticated carrier IN_TRANSIT
  -> persist pickup and external settlement
  -> Paystack transfer of item amount to frozen vendor recipient
  -> transfer.success
  -> order remains IN_TRANSIT until carrier DELIVERED
```

The buyer's delivery and service charges are never paid to the seller. New orders do not require `POST /orders/{orderId}/confirm-delivery`, guest delivery OTP, or guest `confirm-delivery`; those endpoints reject `CARRIER_PICKUP` orders. They remain callable only for migrated orders whose persisted release policy is `BUYER_DELIVERY_CONFIRMATION`.

Paystack payout references are deterministic. Pending or unknown dispatch outcomes are reconciled on a persisted due-time queue through transfer verification. Each worker holds a fenced reconciliation token before provider I/O, and direct dispatch uses the same claim path. An ambiguous timeout, authentication failure, throttling response, conflict, or provider/server outage keeps the same reference for later verification. A narrowly classified definitive rejection, confirmed failure, mismatched reference or transfer ID, or successful response whose exact amount, `NGN` currency, or frozen recipient does not match stops automation in `REQUIRES_ACTION`; ShopSecure never creates a second automatic reference for that settlement. Reversal is terminal and cannot be overwritten by a replayed success event. Production is configured for fully automated transfers and therefore requires transfer OTP to be disabled in the ShopSecure Paystack account. An unexpected `otp` response remains `REQUIRES_ACTION` and never completes settlement.

Pickup confirmation creates durable, independently retried buyer and vendor emails containing the stable ShopSecure tracking link. After Paystack reports transfer success, both participants also receive settlement email and SMS with carrier-pickup wording. Pending or unknown payouts do not claim that funds were credited. Duplicate pickup updates and payout-success events do not resend either notification type.

## Webhooks

| Method | Endpoint | Verification |
|---|---|---|
| `POST` | `/api/webhooks/paystack` | `x-paystack-signature`, HMAC-SHA512 |
| `POST` | `/api/webhooks/terminal` | `X-Terminal-Signature`, HMAC-SHA512 |
| `POST` | `/api/webhooks/chowdeck` | No provider signature; authenticated canonical delivery re-fetch |

Paystack and Terminal signatures are calculated over the exact raw body. These three exact POST paths are public. Neighboring paths and other HTTP methods require member authentication. The unsigned Chowdeck route remains rate-limited; the signed Paystack and Terminal routes are exempt from the application limiter.

Configure the Chowdeck dashboard webhook URL as `https://<public-backend-host>/api/webhooks/chowdeck`. The callback accepts only this identity shape; all other callback fields are ignored:

```json
{
  "payload": {
    "reference": "order-123"
  }
}
```

The submitted reference is an untrusted lookup hint, not pickup evidence. The backend first requires one exact persisted `CHOWDECK` shipment owner across the order and waybill domains, then calls authenticated `GET /relay/delivery/{reference}` and requires the returned reference to match. Only that canonical status and timeline enter the existing monotonic tracking pipeline. Canonical `IN_TRANSIT`/pickup evidence releases item amount only for an eligible paid V19 order; `CONFIRMED` never releases funds, and `DELIVERED` is only missed-pickup recovery plus fulfillment completion. Unknown, non-Chowdeck, inactive, and cross-domain-ambiguous references are acknowledged without a provider call or state change. Duplicate callbacks while tracking remains active repeat the same canonical reconciliation, whose locking, monotonic transitions, pickup marker, unique settlement, and deterministic payout reference prevent duplicate release. Invalid bodies return `400`; a known active shipment whose canonical reconciliation temporarily fails returns `503`, while scheduled polling remains the fallback.

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
APP_BASE_URL=https://<public-backend-host>
FRONTEND_BASE_URL=https://<public-frontend-host>
DATA_ENCRYPTION_KEY=<Base64-encoded 32-byte key>
ORDER_GUEST_TOKEN_TTL=PT168H
PAYSTACK_SECRET_KEY=<live secret>
TERMINAL_AFRICA_ENABLED=true
TERMINAL_AFRICA_BASE_URL=https://api.terminal.africa/v1
TERMINAL_AFRICA_SECRET_KEY=<live secret>
CHOWDECK_HARD_DISABLED=true
CHOWDECK_BASE_URL=https://api.chowdeck.com
CHOWDECK_SECRET_KEY=<secret; required when hard-disabled is false>
CHOWDECK_QUOTE_TTL=PT5M
SHIPPING_DELIVERY_MARKUP=1000.00
SHIPPING_SERVICE_FEE=500.00
SHIPPING_QUOTE_TTL=PT15M
PAYOUT_RECONCILIATION_INITIAL_DELAY_MS=60000
PAYOUT_RECONCILIATION_DELAY_MS=60000
PAYOUT_RECONCILIATION_BATCH_SIZE=50
PAYOUT_RECONCILIATION_RETRY_DELAY=PT1M
WAYBILL_INVITATION_TTL=PT168H
WAYBILL_OTP_MAX_SENDS=5
WAYBILL_OTP_MAX_FAILED_ATTEMPTS=10
WAYBILL_BOOKING_RECONCILIATION_INITIAL_DELAY_MS=60000
WAYBILL_BOOKING_RECONCILIATION_DELAY_MS=60000
WAYBILL_BOOKING_RECONCILIATION_BATCH_SIZE=25
WAYBILL_MAX_BOOKING_ATTEMPTS=5
WAYBILL_BOOKING_RETRY_DELAY=PT5M
WAYBILL_CANCELLATION_RECONCILIATION_INITIAL_DELAY_MS=60000
WAYBILL_CANCELLATION_RECONCILIATION_DELAY_MS=60000
WAYBILL_CANCELLATION_RECONCILIATION_BATCH_SIZE=25
WAYBILL_MAX_CANCELLATION_ATTEMPTS=5
WAYBILL_CANCELLATION_RETRY_DELAY=PT5M
WAYBILL_PAYMENT_RECONCILIATION_INITIAL_DELAY_MS=60000
WAYBILL_PAYMENT_RECONCILIATION_DELAY_MS=60000
WAYBILL_PAYMENT_RECONCILIATION_BATCH_SIZE=50
WAYBILL_PAYMENT_RECONCILIATION_RETRY_DELAY=PT5M
WAYBILL_REFUND_RECONCILIATION_INITIAL_DELAY_MS=60000
WAYBILL_REFUND_RECONCILIATION_DELAY_MS=60000
WAYBILL_REFUND_RECONCILIATION_BATCH_SIZE=25
WAYBILL_REFUND_RECONCILIATION_RETRY_DELAY=PT5M
WAYBILL_REFUND_UNKNOWN_TIMEOUT=PT1H
WAYBILL_NOTIFICATION_INITIAL_DELAY_MS=60000
WAYBILL_NOTIFICATION_DELAY_MS=60000
WAYBILL_NOTIFICATION_BATCH_SIZE=25
WAYBILL_NOTIFICATION_MAX_ATTEMPTS=5
WAYBILL_NOTIFICATION_RETRY_DELAY=PT5M
SCHEDULER_POOL_SIZE=4
TWILIO_SMS_ENABLED=true
TWILIO_ACCOUNT_SID=<sid>
TWILIO_AUTH_TOKEN=<token>
TWILIO_FROM_NUMBER=<sender>
RESEND_API_KEY=<key>
```

`PAYSTACK_VIRTUAL_ACCOUNT_BANK` is optional when Paystack can choose the dedicated-account bank. Reconciliation delays and batch sizes can also be overridden; see `.env.example`.

When `PAYSTACK_SECRET_KEY` starts with `sk_test_`, the bank list exposes only Paystack Test Bank (`001`); use account number `0000000000`. This avoids Paystack's daily cap on resolving live bank accounts in test mode. When creating the test transfer recipient, the backend translates Paystack's account-resolution code (`001`) to its documented Zenith transfer-test code (`057`). Live keys continue using the real Nigerian bank list without translation.

The Docker image activates the `prod` profile. Every run must select exactly one of `local`, `test`, or `prod`; missing, misspelled, and combined profiles are rejected. Production startup rejects disabled Terminal/Twilio providers, invalid security lifetimes, non-HTTPS provider/application URLs, unsafe reconciliation limits, missing enabled-provider credentials, a Chowdeck host other than `https://api.chowdeck.com`, and any Paystack secret that is not an `sk_live_` key. Flyway baselining is disabled in production so an unexpected nonempty schema fails closed. Carrier wallets must be funded separately; Paystack collections do not automatically top them up. Register the Paystack and Terminal webhook URLs before accepting live orders, and configure the Chowdeck dashboard with the public callback URL above before enabling Chowdeck for new shipments.

## Migration Notes

Flyway V7 creates the guest-access, parcel, shipment, quote, payment-receipt, settlement and webhook-deduplication schema. All legacy workflow orders become `MANUAL_REVIEW`: their old address snapshots have no required postal code, and an old wallet debit is not proof of recoverable provider-held funds.

Flyway V8 adds `orders.funding_mode`, receipt-backed DVA wallet credits, and wallet order-payment debits. Existing buyer orders that already have a payment reference remain `DIRECT_DVA`; unselected buyer orders become `WALLET`; vendor-created orders remain `DIRECT_DVA`. Wallet balances are constrained to remain nonnegative.

The V8 balance and transaction-amount checks intentionally fail closed if populated V7 data violates the new financial invariants. Preflight production data for negative wallet balances and nonpositive wallet transaction amounts before deployment.

Flyway V9 adds `PAYSTACK_CHECKOUT` and persisted order payment attempts. Unselected vendor-created orders with no issued payment reference move safely to checkout. Selected or historical `DIRECT_DVA` orders remain unchanged to preserve delayed-transfer handling.

Flyway V20 is the current schema version. V14 adds the independent waybill delivery domain, V15 adds its durable per-party email outbox, V16 adds the frozen vendor payout snapshot plus the complete-at-creation transition, V17 adds custom escrow-order tracking links and durable participant emails, V18 adds Chowdeck Relay control and audit history, V19 adds immutable per-order release policy, pickup evidence, settlement trigger audit fields, and typed order notification intents, and V20 adds order disputes plus their durable email outbox. Waybills do not extend or reuse escrow `orders`.

## Waybill Delivery API (Flyway V14)

### Domain Boundary

Waybills are delivery-only records backed by the V14 `waybills`, `waybill_parties`, `waybill_parcels`, `waybill_invitations`, `waybill_shipments`, `waybill_shipping_quotes`, `waybill_payment_attempts`, `waybill_payment_receipts`, `waybill_refunds`, and `waybill_status_history` tables. V15 adds `waybill_email_notifications`. A waybill is not an escrow order and has no buyer, vendor, item price, settlement, payout, or delivery-confirmation step.

No wallet, DVA, escrow, vendor-settlement, bank-account, withdrawal, or order API applies to a waybill. Do not call `/orders/**`, `/api/wallet/**`, `/api/banks`, or settlement endpoints to fund or complete one. Waybill payment is Paystack Checkout only, and a Terminal Africa `DELIVERED` state completes the waybill automatically.

`creatorRole` identifies the authenticated creator as `SENDER` or `RECIPIENT`; `payerRole` identifies the one party responsible for the complete delivery total. The `SENDER` is always pickup and the `RECIPIENT` is always delivery, independent of who creates or pays.

| Relationship | Initial status | Quote owner | Payment path |
|---|---|---|---|
| `payerRole == creatorRole` | `AWAITING_QUOTE` | Creator | Authenticated creator checkout |
| `payerRole != creatorRole` | `AWAITING_QUOTE` | Creator | Payment-only link created after selection |

The creator party is an immutable account/contact snapshot and `oppositeParty` is always required. Both parties are persisted immediately; no invitation exists until a non-creator payer needs to pay.

### Currency, Geography, And Pricing

Waybills are Nigeria-only and NGN-only:

- Every sender and recipient address requires the two-letter country `NG` and a postal code. Matching is case-insensitive, then persisted output is normalized to uppercase `NG`.
- The backend validates both completed addresses with Terminal Africa before creating or reusing a shipment draft.
- The persisted waybill currency and accepted provider rate currency are `NGN`.
- `declaredValue` is required, positive, and limited to two decimal places. It is parcel valuation sent to the shipping provider; it is not added to the amount payable.

The exact production formula is:

```text
deliveryFee = Terminal rate + SHIPPING_DELIVERY_MARKUP
totalAmount = Terminal rate + SHIPPING_DELIVERY_MARKUP + SHIPPING_SERVICE_FEE
```

Equivalently, `totalAmount = deliveryFee + serviceFee`. The declared value is excluded. Defaults are a `1000.00` NGN markup, `500.00` NGN service fee, and a 15-minute quote TTL. Public/creator quote DTOs intentionally omit the raw Terminal rate and markup; admin waybill DTOs expose them for operations.

### Headers And Response Rules

Authenticated creator requests use:

```http
Authorization: Bearer <jwt>
```

Only the creator can read or mutate their waybill, except for the payer-link operations listed below. Creation, creator checkout, public checkout, and cancellation also require a nonblank `Idempotency-Key` of at most 255 characters. Verified invitation status and checkout require:

```http
X-Waybill-Access-Token: <invitation-scoped-access-token>
```

Missing required headers return `400`. Public summary, OTP, and recovery calls deliberately return `404` for an unknown, expired, or revoked raw invitation token, including a link revoked by reissue. The access-token-protected `/status` and `/payments/checkout` routes return `403` when either bearer token is invalid, expired, revoked, rotated, or mismatched.

All `/waybills/invitations/**` responses, including errors, set:

```http
Cache-Control: no-store
Pragma: no-cache
Referrer-Policy: no-referrer
```

The same three headers are set on `POST /waybills`, `POST /waybills/{publicId}/invitations/reissue`, and creator `POST /waybills/{publicId}/payments/checkout`, because those responses can contain invitation or checkout secrets. Other creator waybill routes do not receive `Referrer-Policy` from the waybill filter. Responses handled by `AdminController` set `Cache-Control: no-store` and `Pragma: no-cache` but do not add `Referrer-Policy`; security-layer rejection responses occur before that controller hook.

Waybill DTO timestamps are `LocalDateTime` values written from the UTC clock and serialized without an offset suffix. Consumers must interpret them as UTC wall time.

### Creator Route Table

All routes below require the member JWT and enforce creator ownership.

| Method | Route | Extra requirement/body | Success |
|---|---|---|---|
| `POST` | `/waybills` | `Idempotency-Key`; `CreateWaybillRequest` | `201 CreateWaybillResponse` |
| `GET` | `/waybills?page=0&size=20` | No body | `200 PageResponse<WaybillDetailsResponse>` |
| `GET` | `/waybills/{publicId}` | No body | `200 WaybillDetailsResponse` |
| `GET` | `/waybills/{publicId}/history` | No body | `200 WaybillStatusHistoryResponse[]` |
| `POST` | `/waybills/{publicId}/invitations/reissue` | No body | `200 WaybillInvitationReissueResponse` |
| `POST` | `/waybills/{publicId}/shipping/quotes` | No body; creator-owned for every payer role | `200 WaybillQuoteResponse[]` |
| `PUT` | `/waybills/{publicId}/shipping/selection` | `{"quoteId": <integer>}` | `200 WaybillQuoteSelectionResponse` |
| `POST` | `/waybills/{publicId}/payments/checkout` | `Idempotency-Key`; `payerRole == creatorRole`; no body | `200 WaybillCheckoutResponse` |
| `GET` | `/waybills/{publicId}/shipping/tracking` | No body; calls provider | `200 WaybillShipmentResponse` |
| `POST` | `/waybills/{publicId}/cancel` | `Idempotency-Key`; JSON `CancelWaybillRequest` (`{}` is valid) | `200 WaybillCancellationResponse` |

Creator lists use zero-based `page`, default `size=20`, maximum `size=100`, and newest-first `createdAt,id` ordering. The page envelope is `content`, `page`, `size`, `totalElements`, `totalPages`, `first`, and `last`. History is a bare array ordered oldest first.

Across creator routes, malformed JSON, validation failures, invalid pagination, missing headers, idempotency conflicts, provider/business failures, and invalid workflow state return `400`; a different authenticated owner receives `403`; and an unknown waybill, quote, or shipment receives `404`. Missing or invalid member authentication is handled by Spring Security and can be `401` or `403`. There is no waybill `409` contract.

Creation is idempotent per creator and key after request normalization. The same key and same normalized body returns HTTP `201` with `replayed=true`; `paymentLink` and `expiresAt` are `null` on creation and replay. Reusing the key with a different body returns `400`. If a selection-generated payment link was lost, use the reissue route.

Creator-paid creation request with both parties supplied:

```json
{
  "creatorRole": "SENDER",
  "payerRole": "SENDER",
  "description": "Two sealed document boxes",
  "declaredValue": 25000.00,
  "creatorAddress": {
    "line1": "1 Marina Road",
    "line2": null,
    "city": "Lagos",
    "state": "Lagos",
    "country": "NG",
    "postalCode": "100001"
  },
  "parcel": {
    "weightKg": 2.500,
    "lengthCm": 40.00,
    "widthCm": 30.00,
    "heightCm": 15.00
  },
  "oppositeParty": {
    "firstName": "Tobi",
    "lastName": "Adebayo",
    "email": "tobi@example.com",
    "phoneNumber": "+2348022222222",
    "address": {
      "line1": "2 Receiver Road",
      "line2": null,
      "city": "Abuja",
      "state": "FCT",
      "country": "NG",
      "postalCode": "900001"
    }
  }
}
```

`creatorRole`, `payerRole`, `description`, `declaredValue`, `creatorAddress`, `parcel`, and `oppositeParty` are required. `line2` is optional. `description` is at most 2,000 characters. Declared value allows at most 36 integer digits and two decimal places. Names are at most 100, email at most 255, and Nigerian phone inputs are normalized to `+234...`. Parcel weight is required and positive with up to nine integer digits and three decimal places; dimensions are optional positive values with up to nine integer digits and two decimal places. Persisted `quantity` is `1` and `parcelType` is `"parcel"`; clients do not submit either field.

Historical V14 party-completion response example (not produced by V16):

```json
{
  "details": {
    "publicId": "c6c39335-d5c8-462e-b24f-34819c960c31",
    "creatorRole": "SENDER",
    "payerType": "INVITED_PAYER",
    "description": "Two sealed document boxes",
    "declaredValue": 25000.00,
    "currency": "NGN",
    "status": "AWAITING_PARTY_DETAILS",
    "paymentStatus": "NOT_STARTED",
    "pricing": null,
    "parties": [
      {
        "role": "SENDER",
        "source": "ACCOUNT_SNAPSHOT",
        "firstName": "Ada",
        "lastName": "Okafor",
        "email": "ada@example.com",
        "phoneNumber": "+2348012345678",
        "address": {
          "line1": "1 Marina Road",
          "line2": null,
          "city": "Lagos",
          "state": "Lagos",
          "country": "NG",
          "postalCode": "100001"
        },
        "accountLinked": true,
        "detailsCompletedAt": "2026-08-15T10:00:00",
        "contactVerifiedAt": "2026-08-15T10:00:00"
      }
    ],
    "parcel": {
      "weightKg": 2.500,
      "lengthCm": 40.00,
      "widthCm": 30.00,
      "heightCm": 15.00,
      "quantity": 1,
      "parcelType": "parcel"
    },
    "selectedQuote": null,
    "shipment": null,
    "invitations": [
      {
        "invitationId": 91,
        "kind": "PARTY_COMPLETION",
        "targetPartyRole": "RECIPIENT",
        "canPay": true,
        "stagedFirstName": null,
        "stagedLastName": null,
        "stagedEmail": null,
        "stagedPhoneNumber": null,
        "active": true,
        "expiresAt": "2026-08-22T10:00:00",
        "verifiedAt": null,
        "detailsCompletedAt": null,
        "paymentCompletedAt": null,
        "revokedAt": null,
        "createdAt": "2026-08-15T10:00:00"
      }
    ],
    "paymentDueAt": null,
    "fundedAt": null,
    "completedAt": null,
    "cancellationReason": null,
    "cancellationRequestedAt": null,
    "createdAt": "2026-08-15T10:00:00",
    "updatedAt": "2026-08-15T10:00:00"
  },
  "invitationLink": "https://shopsecure.app/waybills/invitations/<invitation-token>",
  "expiresAt": "2026-08-22T10:00:00",
  "replayed": false
}
```

### Public Invitation Route Table

The raw invitation token is a path bearer credential. These routes do not use a member JWT.

| Method | Route | Required header/body | Success |
|---|---|---|---|
| `GET` | `/waybills/invitations/{invitationToken}` | None | `200 PublicWaybillInvitationResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/payment-access-otp` | No body | `202 WaybillOtpChallengeResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/verify-otp` | `VerifyWaybillOtpRequest` | `200 WaybillAccessResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/resend-otp` | `{"challengeId":"..."}` | `200 WaybillOtpChallengeResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/access-recovery` | No body; verified invitation only | `202 WaybillOtpChallengeResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/access-recovery/verify` | `VerifyWaybillOtpRequest` | `200 WaybillAccessResponse` |
| `GET` | `/waybills/invitations/{invitationToken}/status` | `X-Waybill-Access-Token` | `200 WaybillAccessResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/payments/checkout` | `X-Waybill-Access-Token`, `Idempotency-Key`; no body | `200 WaybillCheckoutResponse` |

The party-details, payer-claim, and public shipping quote routes shown in older V14 clients are removed. The creator owns quote selection; the public payer only verifies and pays.

Historical V14 recipient quoting example (not callable in V16):

```http
POST /waybills/invitations/{invitationToken}/shipping/quotes
X-Waybill-Access-Token: <verified-access-token>
```

```http
PUT /waybills/invitations/{invitationToken}/shipping/selection
X-Waybill-Access-Token: <verified-access-token>
Content-Type: application/json

{"quoteId": 44}
```

Party completion body:

```json
{
  "firstName": "Tobi",
  "lastName": "Adebayo",
  "email": "tobi@example.com",
  "phoneNumber": "+2348022222222",
  "address": {
    "line1": "2 Receiver Road",
    "line2": null,
    "city": "Abuja",
    "state": "FCT",
    "country": "NG",
    "postalCode": "900001"
  }
}
```

For previously persisted `PAYMENT_ONLY` links only, payer claim has no address because the link holder is not a delivery party. Current creation and reissue never produce this branch:

```json
{
  "firstName": "Kemi",
  "lastName": "Payer",
  "email": "kemi@example.com",
  "phoneNumber": "+2348033333333"
}
```

OTP verification and recovery verification both use:

```json
{
  "challengeId": "otp-challenge-id",
  "code": "123456"
}
```

Successful OTP issue returns exactly `challengeId`, `partyRole`, `emailMasked`, `phoneNumberMasked`, and `expiresInSeconds`. `partyRole` is the selected `SENDER` or `RECIPIENT` payer.

Representative `200 PublicWaybillInvitationResponse` after party details have been staged but before OTP verification:

```json
{
  "waybillPublicId": "c6c39335-d5c8-462e-b24f-34819c960c31",
  "invitationKind": "PARTY_COMPLETION",
  "targetPartyRole": "RECIPIENT",
  "creatorRole": "SENDER",
  "payerType": "INVITED_PAYER",
  "description": "Two sealed document boxes",
  "declaredValue": 25000.00,
  "currency": "NGN",
  "status": "AWAITING_PARTY_DETAILS",
  "paymentStatus": "NOT_STARTED",
  "amountDue": null,
  "canPay": true,
  "detailsRequired": true,
  "inviteeEmailMasked": "t***@***",
  "inviteePhoneNumberMasked": "************22",
  "expiresAt": "2026-08-22T10:00:00"
}
```

Representative initial `200 WaybillAccessResponse` after successful `PARTY_COMPLETION` verification:

```json
{
  "waybillPublicId": "c6c39335-d5c8-462e-b24f-34819c960c31",
  "accessToken": "<new-waybill-access-token>",
  "invitationKind": "PARTY_COMPLETION",
  "targetPartyRole": "RECIPIENT",
  "canCompletePartyDetails": false,
  "canPay": true,
  "detailsCompleted": true,
  "paymentCompleted": false,
  "status": "AWAITING_QUOTE",
  "paymentStatus": "NOT_STARTED",
  "description": "Two sealed document boxes",
  "declaredValue": 25000.00,
  "currency": "NGN",
  "pricing": null,
  "parties": [
    {
      "role": "SENDER",
      "nameMasked": "A** O*****",
      "emailMasked": "a***@***",
      "phoneNumberMasked": "************78",
      "city": "Lagos",
      "state": "Lagos",
      "country": "NG"
    },
    {
      "role": "RECIPIENT",
      "nameMasked": "T*** A******",
      "emailMasked": "t***@***",
      "phoneNumberMasked": "************22",
      "city": "Abuja",
      "state": "FCT",
      "country": "NG"
    }
  ],
  "parcel": {
    "weightKg": 2.500,
    "lengthCm": 40.00,
    "widthCm": 30.00,
    "heightCm": 15.00,
    "quantity": 1,
    "parcelType": "parcel"
  },
  "selectedQuote": null,
  "shipment": null,
  "accessExpiresAt": "2026-08-22T10:00:00"
}
```

`GET .../status` has the same exact field set but always returns `accessToken: null`. A V16 `PAYMENT_ONLY` response has `targetPartyRole` equal to `payerRole`, `canCompletePartyDetails: false`, and both masked delivery parties.

### Invitation, OTP, Token, And Privacy Rules

- `FRONTEND_BASE_URL`, not `APP_BASE_URL`, creates invitation links as `${FRONTEND_BASE_URL}/waybills/invitations/{rawToken}` after trailing slashes are removed.
- The configured invitation lifetime defaults to seven days (`WAYBILL_INVITATION_TTL=PT168H`), but a payment link is capped to the selected quote/payment deadline. Only SHA-256 token hashes are stored.
- Creator list/detail returns active invitation metadata but never the raw link token. Reissue revokes all active invitations and access tokens, supersedes open checkout attempts, and returns exactly one fresh payment link.
- Reissue is allowed only while an opposite-party-paid waybill is `AWAITING_PAYMENT/PENDING` and its payment window is still active.
- OTP is six digits, valid for five minutes, and has a 60-second issue/resend cooldown. Issuing a new challenge invalidates the previous active challenge for that subject.
- The default invitation-wide cap is five total OTP sends, shared by initial verification, resend, and recovery. Attempting to exceed it locks the invitation.
- Each OTP challenge allows at most five wrong-code attempts. The invitation also counts OTP/challenge failures across challenges and locks at ten by default (`WAYBILL_OTP_MAX_FAILED_ATTEMPTS`). A locked invitation has no public unlock endpoint; the creator must reissue when state permits.
- The same code is sent to normalized Nigerian phone and email. Verification binds challenge ID, invitation, purpose, email, and phone.
- Initial verification returns a new `accessToken`; status responses return `accessToken: null`. Access recovery requires the still-active raw invitation and a previously verified invitation, sends to the staged contact, and rotates the access-token hash. The old token immediately returns `403`.
- Cancellation and reissue revoke invitation access. Invitation expiry governs both the raw link and access token.
- Public summary masks contact and exposes only DTO fields: public ID, invitation/role/payer metadata, description, declared value/currency, waybill/payment states, nullable amount due, capability flags, masked invitee contact, and expiry.
- Verified `PAYMENT_ONLY` access returns both parties with masked names, email, phone, and city/state/country only; it omits street and postal code.
- Public email masking is first character plus `***@***`; phone masking reveals only the final two characters. Server request/error logging redacts the raw invitation path segment.

### Quotes, Selection, And Requote

Quote authority always belongs to the authenticated creator through `/waybills/{publicId}/shipping/quotes` and `/waybills/{publicId}/shipping/selection`. Both parties are complete before rates are requested. Public invitation quote routes do not exist.

An unexpired set of `AVAILABLE` rates is returned from cache. Otherwise the backend validates both addresses, creates/reuses the provider shipment draft, obtains rates, prices each rate, and returns them sorted by `deliveryFee`. Quote DTO fields are:

```json
{
  "quoteId": 44,
  "carrierName": "DHL Express",
  "carrierLogo": "https://example.com/carrier.png",
  "serviceDescription": "Express delivery",
  "deliveryFee": 16000.00,
  "serviceFee": 500.00,
  "totalAmount": 16500.00,
  "currency": "NGN",
  "pickupEstimate": "Within 24 hours",
  "deliveryEstimate": "Within 3 days",
  "status": "AVAILABLE",
  "expiresAt": "2026-08-15T10:15:00"
}
```

Selecting an unexpired quote expires every sibling rate, marks the chosen quote `SELECTED`, freezes its pricing onto the waybill, sets `paymentDueAt` to the quote's `expiresAt`, and moves to `AWAITING_PAYMENT/PENDING`. `WaybillQuoteSelectionResponse` contains `selectedQuote` plus nullable `paymentLink` and `paymentLinkExpiresAt`; link fields are populated only when `payerRole != creatorRole`. Re-selecting that same selected quote is idempotent only while the quote and payment window remain unexpired; expired selection returns `400` and must requote. A different selection outside `AWAITING_QUOTE` returns `400`.

Checkout rejects an expired quote/payment window. To requote, call the same actor-specific quote route used for the initial rates. If no captured or unreconciled success exists, this expires the selection, marks open attempts `SUPERSEDED`, clears frozen pricing/deadline, returns the waybill to `AWAITING_QUOTE/NOT_STARTED`, and obtains fresh rates. A later success on a superseded attempt is `LATE` and is fully refunded. If captured-payment evidence exists at expiry, requote stops in `PAYMENT_REVIEW/REVIEW` for operations instead of discarding financial state.

### Paystack Checkout And Payment Classification

Creator and opposite-party checkout return the same DTO. Creator checkout is accepted only when `payerRole == creatorRole`; public checkout requires the verified active payment invitation for the opposite-party payer. Checkout remains `POST /waybills/invitations/{invitationToken}/payments/checkout` with `X-Waybill-Access-Token`, one stable `Idempotency-Key`, and no body.

```json
{
  "waybillPublicId": "c6c39335-d5c8-462e-b24f-34819c960c31",
  "paymentAttemptId": 72,
  "payerRole": "RECIPIENT",
  "status": "AWAITING_PAYMENT",
  "paymentStatus": "PENDING",
  "attemptStatus": "INITIALIZED",
  "amount": 16500.00,
  "currency": "NGN",
  "reference": "waybill-c6c39335d5c8462eb24f34819c960c31-ab12cd34ef56ab78",
  "authorizationUrl": "https://checkout.paystack.com/example",
  "accessCode": "example-access-code",
  "createdAt": "2026-08-15T10:05:00"
}
```

Initialization persists the unique reference and attempt before calling Paystack. Creator checkout resolves the same waybill/idempotency key to that exact attempt before checking current payment state. Invited checkout can replay the same key only while the verified invitation remains active and payment-permitted; after a matched payment marks the invitation complete, another public checkout call returns `403` and `/status` is the source of truth. A different key is rejected while an `INITIALIZING`, `INITIALIZATION_UNKNOWN`, or `INITIALIZED` attempt is open; the caller must retry with the original key. After an explicit `FAILED` or `ABANDONED` attempt, a new key may create another attempt while the quote/payment window remains valid.

An initialization timeout or malformed provider response returns the persisted attempt as `INITIALIZATION_UNKNOWN`; `authorizationUrl` and `accessCode` can be `null`. Scheduled server-to-server verification retries it. `APP_BASE_URL` builds the Paystack callback as `${APP_BASE_URL}/waybills/{publicId}`. A browser redirect is never payment proof.

Only a signed Paystack event or authenticated server-to-server verification is considered. Reference, provider transaction ID, positive two-decimal amount, exact currency, provider occurrence time, selected/used quote, frozen total, and fundable state are checked before funding.

| Captured outcome | Receipt classification | Backend action |
|---|---|---|
| Exact NGN amount, on time, safe state | `MATCHED` | Confirm once, set `SHIPMENT_BOOKING`, mark invited payment complete, supersede other open attempts, queue async booking |
| Amount below frozen total | `UNDERPAID` | Never partially funds; queue a full captured-amount refund |
| Amount above frozen total | `OVERPAID` | Never keeps the excess or funds; queue a full captured-amount refund |
| Success after deadline, cancellation/refund context, or superseded attempt | `LATE` | Queue a full captured-amount refund |
| Different successful attempt after funding | `DUPLICATE` | Queue a full refund without changing the already-funded root waybill |
| Captured event is internally inconsistent but amount/transaction can be safely identified | `FAILED` | Queue a full refund and move the root to review/refund flow where applicable |
| Unsafe success, such as missing/invalid transaction ID, amount, reference, or currency | `FAILED` | Do not guess or auto-refund; retain verification retries and move an unfunded waybill to payment/manual review |

Paystack `FAILED` and `ABANDONED` close the attempt without funding. `PENDING`/unknown outcomes remain open for reconciliation. Receipt event keys deduplicate webhook retries, and the scheduler also reconciles `SUPERSEDED` attempts so a late capture is not lost.

### Async Shipping, Tracking, Cancellation, And Refunds

An exact payment commits before provider booking runs asynchronously. Terminal booking first tracks the persisted quick-shipment draft to recover safely from an ambiguous prior pickup request. Chowdeck has no remote pre-payment draft and creates the Relay delivery with the persisted fee ID only after payment. An already active shipment is accepted rather than booked twice; an already delivered shipment completes the waybill. The service checks the persisted provider's wallet liquidity against the raw provider rate before booking. Address validation and the public state/city catalogue remain backed by Terminal or the local stub.

Booking failures move to `SHIPPING_FAILED` and retry from persisted state. Defaults are five attempts with linear delays of `WAYBILL_BOOKING_RETRY_DELAY * attempt`; exhaustion or a provider-terminal draft becomes `MANUAL_REVIEW`. Active booking produces `READY_FOR_DELIVERY`. Creator tracking and signed Terminal webhooks apply monotonic provider updates; Chowdeck callbacks request an authenticated tracking refresh, with scheduled reconciliation as fallback. Carrier `DELIVERED` sets `completedAt` and `COMPLETED`; there is no user delivery-confirmation call.

When a paid waybill first reaches `READY_FOR_DELIVERY`, the booking transaction atomically creates one `AWAITING_PICKUP` email-outbox row for the sender and one for the recipient. Once Terminal reports `CONFIRMED` or `IN_TRANSIT`, both receive **Payment confirmed - package entered pickup stage** with the package description, party role, recorded pickup-stage time, tracking reference, and validated HTTPS Terminal/carrier tracking link. `PENDING`, `processing`, and `queued` carrier states are polled rather than described as confirmed pickup. The unique `(waybill, party, notificationType)` key prevents booking-recovery duplicates. If the booking response has no link, the worker polls Terminal tracking and requires an exact provider-shipment ID match before storing or emailing the URL; it never sends the no-link version. Each delivery is claimed in a short database transaction, sent outside that transaction with the stable Resend idempotency key `waybill-awaiting-pickup/{notificationId}`, and finalized separately. Resend failures are isolated per recipient and retried with linear backoff and five delivery attempts by default. Recoverable waybill states defer the row; messages made obsolete by cancellation, refund, or completion become `SKIPPED`. A pending send older than the provider's idempotency retention is marked `FAILED` for manual review instead of risking a duplicate. Notification failure never rolls back payment or carrier booking.

Cancellation is creator-only and idempotent. It accepts an optional `reason` of at most 500 characters. It can start from `AWAITING_PARTY_DETAILS`, `AWAITING_QUOTE`, `AWAITING_PAYMENT`, `PAYMENT_REVIEW`, `SHIPMENT_BOOKING`, `READY_FOR_DELIVERY`, `SHIPPING_FAILED`, and a narrowly safe `MANUAL_REVIEW`. An in-transit/delivered shipment, completed waybill, or refunded waybill cannot be cancelled. The first request records the key/reason/time, revokes invitations, and moves to `CANCELLATION_PENDING` before contacting the carrier.

Carrier cancellation is crash-safe and scheduler-backed. When a shipment exists, each dispatch persists `cancellationAttempt` and `nextCancellationAttemptAt` before the provider call; a waybill with no shipment can finalize immediately. While the shipment remains `DRAFT`, `PENDING`, or `CONFIRMED`, the fixed-delay scheduler selects due `CANCELLATION_PENDING` rows in bounded batches and retries with linear backoff of `WAYBILL_CANCELLATION_RETRY_DELAY * attempt`. Reusing the same cancellation key returns current state and dispatches again only when due; a different key is rejected. Five failed or unconfirmed attempts by default, an unsafe provider state, or a mismatched response clears automatic retry and moves the waybill to `MANUAL_REVIEW`.

A safe confirmed carrier cancellation becomes `CANCELLED` when no captured payment exists. Every safely reconciled captured payment is instead queued for a full Paystack refund. An unreconciled success, delivery during or after cancellation, or refund uncertainty also moves to `MANUAL_REVIEW` rather than claiming cancellation or refund success. A `MANUAL_REVIEW` waybill can enter automated cancellation only in the narrow code path where payment is already `CONFIRMED` and a persisted shipment still has `DRAFT`, `PENDING`, or `CONFIRMED` status.

Example cancellation/refund response:

```json
{
  "waybillPublicId": "c6c39335-d5c8-462e-b24f-34819c960c31",
  "status": "REFUND_PENDING",
  "paymentStatus": "REFUND_PENDING",
  "reason": "Delivery no longer required",
  "cancellationRequestedAt": "2026-08-15T11:00:00",
  "refund": {
    "refundId": 18,
    "providerRefundId": null,
    "providerRefundReference": null,
    "amount": 16500.00,
    "currency": "NGN",
    "status": "REQUESTED",
    "reason": "Delivery no longer required",
    "failureReason": null,
    "providerCreatedAt": null,
    "providerUpdatedAt": null,
    "createdAt": "2026-08-15T11:00:00",
    "updatedAt": "2026-08-15T11:00:00",
    "completedAt": null
  }
}
```

Refund dispatch occurs after commit and is reconciled asynchronously. Refunds are full captured-amount refunds, one persisted refund per payment attempt; there is no partial-refund API. `REQUESTED` is dispatched after commit, `DISPATCHING` ambiguity becomes `DISPATCH_UNKNOWN`, and provider IDs are verified on the reconciliation schedule. A processed root-affecting refund becomes `REFUNDED/REFUNDED`; provider `FAILED` or `NEEDS_ATTENTION` becomes `MANUAL_REVIEW/REVIEW`. A dispatch-unknown refund with no provider refund ID moves to manual review after `WAYBILL_REFUND_UNKNOWN_TIMEOUT` (one hour by default). A processed duplicate-payment refund does not overwrite the already-funded root waybill. There is no creator, public, or admin refund mutation and no standalone creator/public refund read route. Creator detail exposes root status only; replaying cancellation with the original key returns the current root refund DTO, while admin detail embeds refund records.

### Waybill State Values

`WaybillStatus`:

| Value | Meaning |
|---|---|
| `AWAITING_PARTY_DETAILS` | Opposite sender/recipient details still require invitation verification |
| `AWAITING_QUOTE` | Both parties exist; creator can request/select shipping |
| `AWAITING_PAYMENT` | Quote and total are frozen until `paymentDueAt` |
| `PAYMENT_REVIEW` | Captured/unsafe/expired payment context requires reconciliation |
| `SHIPMENT_BOOKING` | Exact payment confirmed; async pickup booking is running |
| `READY_FOR_DELIVERY` | Carrier booking is active |
| `SHIPPING_FAILED` | Booking/carrier failure is eligible for automated recovery or support |
| `CANCELLATION_PENDING` | Cancellation recorded; carrier/payment reconciliation not final |
| `REFUND_PENDING` | A root-affecting full refund is in progress |
| `COMPLETED` | Carrier confirmed delivery |
| `CANCELLED` | Carrier cancellation finalized with no root refund outstanding |
| `REFUNDED` | Root payment refund processed |
| `MANUAL_REVIEW` | Automation stopped because state was unsafe or ambiguous |

`WaybillPaymentStatus`: `NOT_STARTED`, `PENDING`, `CONFIRMED`, `REFUND_PENDING`, `REFUNDED`, `REVIEW`.

`WaybillPaymentAttemptStatus`: `INITIALIZING`, `INITIALIZATION_UNKNOWN`, `INITIALIZED`, `SUCCESS`, `FAILED`, `ABANDONED`, `SUPERSEDED`.

`WaybillPaymentClassification`: `MATCHED`, `UNDERPAID`, `OVERPAID`, `DUPLICATE`, `LATE`, `FAILED`.

`WaybillRefundStatus`: `REQUESTED`, `DISPATCHING`, `DISPATCH_UNKNOWN`, `PENDING`, `PROCESSING`, `PROCESSED`, `FAILED`, `MANUAL_REVIEW`.

`ShippingQuoteStatus`: `AVAILABLE`, `SELECTED`, `USED`, `EXPIRED`.

Provider payment status: `PENDING`, `SUCCESS`, `FAILED`, `ABANDONED`, `UNKNOWN`. Shipment status: `DRAFT`, `CONFIRMED`, `PENDING`, `IN_TRANSIT`, `DELIVERED`, `CANCELLED`, `FAILED`, `UNKNOWN`.

`CANCELLED`, `REFUNDED`, and `COMPLETED` are closed for normal user workflow, but a later contradictory carrier event can move a cancelled/refunded waybill to `MANUAL_REVIEW`. Consumers should revalidate rather than treating cached closed state as immutable.

### Waybill Admin Routes

Both routes require an `ADMIN` member JWT and return no-store responses:

| Method | Route | Contract |
|---|---|---|
| `GET` | `/api/admin/waybills?creatorId=&status=&paymentStatus=&page=0&size=20` | `PageResponse<AdminWaybillSummaryResponse>` |
| `GET` | `/api/admin/waybills/{waybillId}` | `AdminWaybillDetailResponse`; `{waybillId}` is the numeric database ID, not `publicId` |

Filters are optional and combined with AND. Admin pages are zero-based, default to 20, allow 1 through 100, and sort by numeric ID descending. Detail embeds at most the newest 100 quotes, attempts, receipts, refunds, invitations, and history entries; parties are role-ordered.

The existing `/api/admin/dashboard` aggregates only the escrow order, wallet, withdrawal, settlement, order-shipment, and order-payment tables. V14 waybill counts, values, payment classifications, refunds, and shipment states are not included; use the two waybill read routes instead.

Admin contact values are masked, street/postal addresses are omitted, and payment customer email is masked. Invitation token/access hashes and OTP challenge ID, payment access code/authorization URL/idempotency key, waybill creation/cancellation idempotency keys and request hash, quote provider rate/internal IDs, carrier logo, and shipment tracking URL are omitted. See `ADMIN_API.md` for the exact nested field contract.

### Chowdeck Admin Control

These routes require an `ADMIN` member JWT and return no-store responses:

| Method | Route | Contract |
|---|---|---|
| `GET` | `/api/admin/shipping/chowdeck-control` | Current configured/effective mode, hard-kill state, version, and last change metadata |
| `PUT` | `/api/admin/shipping/chowdeck-control` | Audited compare-and-set update with `mode`, `expectedVersion`, and `reason` |
| `GET` | `/api/admin/shipping/chowdeck-control/history?page=0&size=20` | Newest-first immutable control history |

The update body is `{ "mode": "OFF|SHADOW|ON", "expectedVersion": 0, "reason": "..." }`. A stale version returns `409`; refresh the control and require the administrator to review the new state before retrying. The response deliberately exposes neither the Chowdeck key nor wallet balance. `configuredMode: "ON"` with `effectiveMode: "OFF"` and `hardKillActive: true` means deployment configuration still prevents Chowdeck calls.

### Waybill Configuration And Webhooks

The current `.env.example` and application properties expose these waybill-relevant settings:

| Environment variable | Default/use |
|---|---|
| `APP_BASE_URL` | `http://localhost:8080`; Paystack waybill callback origin |
| `FRONTEND_BASE_URL` | `http://localhost:3000`; invitation-link origin |
| `WAYBILL_INVITATION_TTL` | `PT168H` |
| `WAYBILL_OTP_MAX_SENDS` | `5` total sends per invitation |
| `WAYBILL_OTP_MAX_FAILED_ATTEMPTS` | `10` failures per invitation |
| `WAYBILL_BOOKING_RECONCILIATION_INITIAL_DELAY_MS` | `60000` |
| `WAYBILL_BOOKING_RECONCILIATION_DELAY_MS` | `60000` |
| `WAYBILL_BOOKING_RECONCILIATION_BATCH_SIZE` | `25`, runtime capped at 100 |
| `WAYBILL_MAX_BOOKING_ATTEMPTS` | `5` |
| `WAYBILL_BOOKING_RETRY_DELAY` | `PT5M` |
| `WAYBILL_CANCELLATION_RECONCILIATION_INITIAL_DELAY_MS` | `60000` |
| `WAYBILL_CANCELLATION_RECONCILIATION_DELAY_MS` | `60000` fixed scheduler delay |
| `WAYBILL_CANCELLATION_RECONCILIATION_BATCH_SIZE` | `25`, runtime capped at 100 |
| `WAYBILL_MAX_CANCELLATION_ATTEMPTS` | `5` |
| `WAYBILL_CANCELLATION_RETRY_DELAY` | `PT5M`; linear persisted backoff base |
| `WAYBILL_PAYMENT_RECONCILIATION_INITIAL_DELAY_MS` | `60000` |
| `WAYBILL_PAYMENT_RECONCILIATION_DELAY_MS` | `60000` |
| `WAYBILL_PAYMENT_RECONCILIATION_BATCH_SIZE` | `50`, runtime capped at 100 |
| `WAYBILL_PAYMENT_RECONCILIATION_RETRY_DELAY` | `PT5M` |
| `WAYBILL_REFUND_RECONCILIATION_INITIAL_DELAY_MS` | `60000` |
| `WAYBILL_REFUND_RECONCILIATION_DELAY_MS` | `60000` |
| `WAYBILL_REFUND_RECONCILIATION_BATCH_SIZE` | `25`, runtime capped at 100 |
| `WAYBILL_REFUND_RECONCILIATION_RETRY_DELAY` | `PT5M` |
| `WAYBILL_REFUND_UNKNOWN_TIMEOUT` | `PT1H` |
| `WAYBILL_NOTIFICATION_INITIAL_DELAY_MS` | `60000` |
| `WAYBILL_NOTIFICATION_DELAY_MS` | `60000` persisted-email retry scan |
| `WAYBILL_NOTIFICATION_BATCH_SIZE` | `25`, runtime capped at 100 |
| `WAYBILL_NOTIFICATION_MAX_ATTEMPTS` | `5` Resend delivery attempts per recipient |
| `WAYBILL_NOTIFICATION_RETRY_DELAY` | `PT5M`; linear retry base |
| `SCHEDULER_POOL_SIZE` | `4`; production requires at least 2 |
| `TERMINAL_AFRICA_ENABLED` | Base default `false`, production default/requirement `true` |
| `TERMINAL_AFRICA_BASE_URL` | Production `https://api.terminal.africa/v1`; local default sandbox |
| `TERMINAL_AFRICA_SECRET_KEY` | Required when Terminal is enabled/production |
| `CHOWDECK_HARD_DISABLED` | Default `true`; environment emergency stop and effective-mode override |
| `CHOWDECK_BASE_URL` | Production requires exactly `https://api.chowdeck.com` |
| `CHOWDECK_SECRET_KEY` | Required when `CHOWDECK_HARD_DISABLED=false`; never returned by an API |
| `CHOWDECK_QUOTE_TTL` | `PT5M`; local Relay fee-ID validity window |
| `SHIPPING_STUB_WEBHOOK_SECRET` | Local stub signature secret; application default `local-shipping-webhook-secret` |
| `SHIPPING_DELIVERY_MARKUP` | `1000.00` NGN |
| `SHIPPING_SERVICE_FEE` | `500.00` NGN |
| `SHIPPING_QUOTE_TTL` | `PT15M` |
| `PAYSTACK_SECRET_KEY` | Required; production requires `sk_live_...` |
| `PAYSTACK_BASE_URL` | `https://api.paystack.co` |
| `TWILIO_SMS_ENABLED` | Base default `false`, production default/requirement `true` |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_NUMBER`, `TWILIO_BASE_URL` | OTP SMS provider settings |
| `RESEND_API_KEY` | OTP and waybill lifecycle email provider key |

Database/JWT/profile settings (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `JWT_EXPIRATION`, `SPRING_PROFILES_ACTIVE`) remain required as described above. Local Terminal-disabled runs use the shipping stub; non-load-test application profiles use Paystack for payment/refund operations.

Provider callbacks use three exact public POST routes:

| Endpoint | Events | Verification |
|---|---|---|
| `POST /api/webhooks/paystack` | `charge.success`, `charge.failed`, `charge.abandoned`, and refund lifecycle events | `x-paystack-signature`, HMAC-SHA512 over exact raw body |
| `POST /api/webhooks/terminal` | Shipment status updates routed by provider shipment ID | Terminal: `X-Terminal-Signature`, HMAC-SHA512; local stub: `x-shipping-signature`, HMAC-SHA256 |
| `POST /api/webhooks/chowdeck` | Relay delivery callback containing `payload.reference` | No signature; callback status is ignored and the delivery is fetched through authenticated Chowdeck API |

The browser must never call any webhook or send provider secrets.
