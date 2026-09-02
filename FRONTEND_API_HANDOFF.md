# ShopSecure Frontend API Handoff

Last updated: 2026-08-25

## Contract Status

This document describes the backend contract currently implemented in the ShopSecure working tree. The guest, shipping, wallet-first buyer payment, order-specific guest checkout, public receiver-confirmed escrow payout, persisted legacy carrier-pickup payout, standalone waybill delivery, order-dispute intake, admin operations, and audited Chowdeck control flows are implemented.

The frontend must be released with the matching backend code and Flyway V20 migration. V20 is the current schema version. There is no API version prefix, so an old frontend cannot safely use the new backend contract.

The following V16 complete-at-creation rules, V17 tracking additions, V18 provider-control rules, V19 carrier-pickup settlement rules, and V20 dispute rules are authoritative. Older examples later in this document that mention create-time order links, public party completion, public quote selection, `CreateWaybillRequest.payerType`, or new `AWAITING_PARTY_DETAILS` records are historical V14/V15 material and must not be implemented.

Important V16-V20 changes:

- Every order funded after V17 receives one public bearer tracking link. The same link is returned to the creator and verified guest and emailed independently to the buyer and vendor; opening it requires no login or OTP.
- New orders persist `escrowReleasePolicy: "BUYER_DELIVERY_CONFIRMATION"`. Carrier pickup records evidence but does not release money; the tracking-page confirmation action queues item amount only to the frozen vendor bank recipient.
- Already-created `CARRIER_PICKUP` orders retain automatic pickup payout. There is no data migration. Always branch on the persisted `escrowReleasePolicy`; never infer policy from creation date.
- The first pickup observation records `carrierPickupConfirmedAt` and creates independently retried pickup emails for buyer and vendor. No frontend notification mutation is required.
- Any visitor can submit one reason-only dispute against a funded order's numeric ID. The backend returns one generic `202`, does not change lifecycle state, and independently emails buyer, vendor, and operations.
- Paid waybills now create durable sender and recipient pickup-stage emails containing the validated Terminal/carrier tracking link after the carrier confirms booking; no frontend notification mutation is required.
- Public cached Nigerian state and city lookup routes are available under `/api/locations/**`; the frontend must not call Terminal Africa directly.
- `/waybills/**` is a completed delivery-only domain, separate from escrow `/orders/**`.
- Waybills use `SENDER`/`RECIPIENT`, Paystack Checkout only, and automatic completion on carrier delivery. Wallet, DVA, escrow, delivery-confirmation, and vendor-settlement APIs do not apply.
- A read-only `/api/admin/**` API is available to verified accounts whose member role is `ADMIN`.
- The admin frontend can inspect users, orders, waybills, wallets, withdrawals, settlements, shipments, payment attempts, payment receipts, and aggregate operational metrics. It cannot mutate them.
- The one admin mutation is an audited `OFF | SHADOW | ON` Chowdeck control. Quote selection remains provider-neutral. The bearer tracking snapshot intentionally exposes the persisted shipment provider but does not provide a customer-facing carrier toggle.
- All routes previously marked `TO BUILD` for this workflow now exist.
- `POST /orders/{orderId}/pay` remains removed. Buyer-created orders now use the explicit `POST /orders/{orderId}/payments/wallet` mutation.
- Every order is complete at creation: `counterparty`, `parcel`, `creatorAddress`, and `vendorBankAccount` are required for both creator roles.
- The authenticated order creator always requests and selects shipping quotes.
- New buyer-created orders are wallet funded and return no buyer link. New vendor-created orders return `buyerPaymentLink` only after the vendor selects a quote.
- The public order page verifies the recorded buyer for payment only. It never collects party, address, parcel, bank, or quote data.
- DVA top-ups credit the member wallet but never automatically pay an order. The registered buyer manually retries wallet payment.
- Personal DVAs remain available to every registered user and are not used for new guest-order checkout matching.
- A buyer pays item amount, customer delivery fee, and service fee.
- A seller receives item amount only.
- Buyer payment access uses an order-scoped token, not a member JWT.
- Buyer and vendor are roles selected per order, not permanent account roles.
- Shipping addresses require `NG` and a postal code.
- The vendor payout account is resolved and frozen during order creation; settlement never asks the link holder for bank data.
- Every waybill is complete at creation and accepts `payerRole: "SENDER" | "RECIPIENT"` plus a required `oppositeParty`.
- The waybill creator always selects the quote. A payment-only link is returned from selection only when `payerRole !== creatorRole`.
- Orders, wallet transactions, and wallet withdrawals use zero-based pagination.
- Business and invalid-state errors normally return `400`; an optimistic Chowdeck admin-control version conflict returns `409`.

## V20 Order Dispute Contract

The customer action uses the numeric `orderId` already present in member order details and public tracking responses. It does not send a member JWT, share token, guest token, tracking token, OTP, reporter identity, or idempotency key:

```http
POST /orders/{orderId}/disputes
Content-Type: application/json

{
  "reason": "The package arrived with visible screen damage."
}
```

```ts
export interface CreateOrderDisputeRequest {
  reason: string;
}

export interface CreateOrderDisputeResponse {
  message: string;
}
```

The backend accepts only canonical positive decimal IDs. `reason` must be nonblank, is limited to 2000 characters, permits ordinary line breaks/tabs, and rejects other control characters. Keep it only in component state; do not place the reason in a URL, analytics payload, console log, browser storage, cache, or error-reporting context.

Every syntactically valid request returns `202` and exactly this body, including unknown, unfunded, and already-disputed IDs:

```json
{
  "message": "If the order is eligible, the dispute has been received and the ShopSecure team will contact both parties."
}
```

Do not infer order existence or dispute creation from this response. Show the same acknowledgement text. `400` means the ID/body is malformed, `413` means the request exceeds 16 KiB, and `429` means the public abuse limit was reached; honor `Retry-After`. Submission is notification-only and does not cancel delivery, freeze settlement, reverse payout, or promise a refund. Do not disable other order actions unless a later authoritative order response changes them.

Admin dispute reads are:

```text
GET /api/admin/disputes?orderId=&status=&page=0&size=20
GET /api/admin/disputes/{disputeId}
```

```ts
export type OrderDisputeStatus =
  | "OPEN"
  | "UNDER_REVIEW"
  | "RESOLVED"
  | "REJECTED";

export type OrderDisputeNotificationAudience = "BUYER" | "VENDOR" | "OPERATIONS";
export type OrderDisputeNotificationStatus = "PENDING" | "SENT" | "FAILED";

export interface AdminOrderDisputeSummaryResponse {
  id: number;
  orderId: number;
  status: OrderDisputeStatus;
  orderStatus: OrderStatus;
  paymentStatus: PaymentStatus;
  settlementStatus: SettlementStatus;
  reasonPreview: string;
  createdAt: string;
  updatedAt: string;
}

export interface AdminOrderDisputeDetailResponse {
  summary: AdminOrderDisputeSummaryResponse;
  itemDescription: string;
  reason: string;
  buyer: AdminOrderDisputeParty | null;
  vendor: AdminOrderDisputeParty | null;
  notifications: AdminOrderDisputeNotification[];
}

export interface AdminOrderDisputeParty {
  firstName: string;
  lastName: string;
  maskedEmail: string;
  maskedPhoneNumber: string;
}

export interface AdminOrderDisputeNotification {
  audience: OrderDisputeNotificationAudience;
  status: OrderDisputeNotificationStatus;
  attemptCount: number;
  nextAttemptAt: string | null;
  sentAt: string | null;
  failureReason: string | null;
}
```

`AdminDashboardResponse.operations` additionally includes `disputesByStatus: Record<string, number>` and `openDisputes: number`. V20 admin dispute APIs are read-only; do not render resolve/reject mutations that do not exist.

## Frontend Order Tracking Implementation Brief

Register this public frontend route:

```text
/orders/tracking/:trackingToken
```

The backend returns the complete browser URL in `shipping.shopSecureTrackingUrl` after payment is confirmed. Use that exact URL; do not construct, alter, persist, or log the token. The corresponding `shipping.trackingAvailableAt` records when tracking access was activated.

The tracking page calls the backend without a JWT:

```text
GET /orders/tracking/{trackingToken}
```

For a current `BUYER_DELIVERY_CONFIRMATION` order, the same page submits the payout-triggering action without a JWT, guest token, or OTP:

```http
POST /orders/tracking/{trackingToken}/confirm-delivery
Idempotency-Key: <stable-client-generated-key>
Content-Type: application/json

{ "confirmation": true }
```

Create one idempotency key for the order and retain it across timeouts/retries. Render an explicit final confirmation before sending, disable repeat clicks while the request is unresolved, and hide the action after `deliveryConfirmedAt` becomes non-null or escrow leaves `NOT_STARTED`.

```ts
export type PublicOrderTrackingStatus =
  | "BOOKING"
  | "AWAITING_PICKUP"
  | "IN_TRANSIT"
  | "DELIVERED"
  | "EXCEPTION"
  | "CANCELLED";

export interface PublicTrackingParty {
  role: OrderPartyRole;
  firstName: string;
  lastName: string;
  email: string;
  phoneNumber: string;
  address: AddressResponse;
}

export interface PublicTrackingPayoutAccount {
  bankAccountLast4: string;
  bankCode: string;
  bankName: string;
  bankAccountName: string;
}

export interface PublicTrackingParcel {
  weightKg: number;
  lengthCm: number | null;
  widthCm: number | null;
  heightCm: number | null;
  quantity: number;
  parcelType: string;
}

export interface PublicTrackingQuote {
  id: number;
  providerRateId: string;
  providerInternalId: string | null;
  carrierName: string;
  carrierLogo: string | null;
  serviceDescription: string | null;
  providerAmount: number;
  deliveryMarkup: number;
  deliveryFee: number;
  serviceFee: number;
  buyerTotal: number;
  currency: string;
  pickupEstimate: string | null;
  deliveryEstimate: string | null;
  selectedAt: string | null;
  expiresAt: string;
}

export interface PublicTrackingShipment {
  provider: string;
  providerShipmentId: string | null;
  status: ShipmentStatus;
  trackingReference: string | null;
  trackingUrl: string | null;
  selectedQuote: PublicTrackingQuote | null;
  providerUpdatedAt: string | null;
  updatedAt: string;
}

export interface PublicTrackingEscrow {
  releasePolicy: EscrowReleasePolicy;
  status: SettlementStatus;
  settlementType: SettlementType | null;
  trigger: "BUYER_DELIVERY_CONFIRMATION" | "CARRIER_PICKUP" | null;
  amount: number;
  amountPending: number;
  amountReleased: number;
  currency: string;
  payoutReference: string | null;
  transferId: string | null;
  failureReason: string | null;
  triggeredAt: string | null;
  completedAt: string | null;
}

export interface PublicOrderTrackingResponse {
  orderId: number;
  itemDescription: string;
  itemAmount: number;
  currency: string;
  creatorRole: OrderPartyRole;
  fundingMode: OrderFundingMode;
  orderStatus: OrderStatus;
  trackingStatus: PublicOrderTrackingStatus;
  paymentStatus: PaymentStatus;
  paymentReference: string | null;
  buyer: PublicTrackingParty;
  vendor: PublicTrackingParty;
  vendorPayoutAccount: PublicTrackingPayoutAccount | null;
  pricing: PricingBreakdownResponse | null;
  parcel: PublicTrackingParcel | null;
  shipment: PublicTrackingShipment | null;
  escrow: PublicTrackingEscrow;
  fundedAt: string | null;
  deliveryConfirmedAt: string | null;
  carrierPickupConfirmedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  trackingAvailableAt: string;
  trackingExpiresAt: string;
  updatedAt: string;
}
```

Poll the endpoint approximately every 30 seconds while the page is visible and either shipment tracking is active (`BOOKING`, `AWAITING_PICKUP`, or `IN_TRANSIT`) or `escrow.status` is `PENDING`/`DISPATCH_UNKNOWN`. A delivered parcel can still have a payout awaiting final verification, so do not stop solely because `trackingStatus` is `DELIVERED`. Stop routine polling when shipment and escrow are both terminal; provide manual refresh for `EXCEPTION` and `REQUIRES_ACTION`. Terminal webhooks plus Chowdeck callback-triggered authenticated refresh and fallback polling update persisted state, so the custom URL never changes and the browser must never call a carrier directly.

No JWT, guest token, or OTP is sent. Anyone with the URL can read the complete buyer/vendor contact and address snapshots, pricing, parcel, shipment, and escrow amounts and can submit delivery confirmation once the order is eligible. The same tracking link is emailed to buyer and vendor, so possession of the URL is the authorization boundary. Use `cache: "no-store"`, set the page-level referrer policy to `no-referrer`, and exclude the full URL and response body from analytics, session replay, link previews, error reports, and client logs. Do not load third-party scripts or assets on this page. A `404` means the link is invalid, expired, revoked, or unavailable; render one generic unavailable state.

## Frontend Waybill Implementation Brief

Build **Send a Package** as a new frontend domain. Do not reuse order, wallet, DVA, escrow, settlement, or delivery-confirmation screens and API clients. Use [V16 Changed DTOs](#v16-changed-dtos) and [Exact Waybill DTOs](#exact-waybill-dtos) as the current field-level references.

### Required Deployment Configuration

Set these backend environment variables on Render:

```text
APP_BASE_URL=https://<deployed-backend-host>
FRONTEND_BASE_URL=https://<deployed-frontend-host>
```

`FRONTEND_BASE_URL` is required in production and must use HTTPS. The backend uses it to return shareable links in this exact form:

```text
https://<deployed-frontend-host>/waybills/invitations/{invitationToken}
```

The frontend must register `/waybills/invitations/:invitationToken` as a public page. API requests from that page still go to the backend origin.

### Screens To Build

1. `/send-package`: authenticated create form.
2. `/waybills`: authenticated creator list.
3. `/waybills/:publicId`: authenticated creator detail, quote, payment, tracking, cancellation, and share-link actions.
4. `/waybills/invitations/:invitationToken`: public non-creator payer OTP and payment flow.
5. `/admin/waybills` and `/admin/waybills/:waybillId`: read-only admin operations views, if the admin frontend is in scope.

### Creator Flow

1. Collect `creatorRole`, `payerRole`, description, declared value, the creator's Nigerian address, parcel measurements, and a complete `oppositeParty`. Either creator role can select either payer role.
2. Generate one stable UUID idempotency key and call `POST /waybills` with the member JWT and `Idempotency-Key`.
3. Creation always returns `paymentLink: null`; do not render a share action yet.
4. In `AWAITING_QUOTE`, call `POST /waybills/{publicId}/shipping/quotes`, display the returned carrier options, and select one with `PUT /waybills/{publicId}/shipping/selection`.
5. Selection returns `selectedQuote`, nullable `paymentLink`, and nullable `paymentLinkExpiresAt`. If a link is returned, show a share action using that exact URL; never construct or alter the token.
6. When `payerRole === creatorRole`, create a stable checkout key and call `POST /waybills/{publicId}/payments/checkout`. When the roles differ, only the verified payment-link holder can call public checkout.
7. Refresh creator state with `GET /waybills/{publicId}`. Use `GET /waybills/{publicId}/shipping/tracking` only for an explicit tracking refresh because it calls Terminal Africa.
8. Cancel with `POST /waybills/{publicId}/cancel`, one stable `Idempotency-Key`, and optional `{ "reason": "..." }`. Reuse the same key after a timeout or while cancellation is pending.

### Public Invitation Flow

1. Read the raw token from the frontend route and call `GET /waybills/invitations/{invitationToken}` without a member JWT.
2. The summary is `PAYMENT_ONLY` with `detailsRequired=false`. Call `/payment-access-otp`; there is no public party-details or payer-claim form.
3. Submit the returned `{ challengeId, code }` to `/verify-otp`.
4. Keep the returned `accessToken` only in memory or session-scoped storage. Send it as `X-Waybill-Access-Token` to `/status` and `/payments/checkout`. Never put it in query parameters, analytics, logs, persistent query caches, or local storage.
5. If the access-token response was lost or rotated, call `/access-recovery`, verify the new OTP at `/access-recovery/verify`, and replace the old token.
6. Initialize checkout when `canPay` is true and the waybill is `AWAITING_PAYMENT`. Use one stable checkout `Idempotency-Key` and no request body. The public payer cannot request or select quotes.

### Status Handling

| Status | Frontend behavior |
|---|---|
| `AWAITING_PARTY_DETAILS` | Historical rows only; show support/legacy state. |
| `AWAITING_QUOTE` | Enable creator quote controls for every payer-role combination. |
| `AWAITING_PAYMENT` | Enable the configured payer's checkout flow. |
| `PAYMENT_REVIEW` | Disable payment and cancellation assumptions; show support/review state. |
| `SHIPMENT_BOOKING` | Show payment confirmed and carrier booking in progress. |
| `READY_FOR_DELIVERY` | Show tracking information and delivery in progress. |
| `SHIPPING_FAILED` | Show retry/review messaging; do not create another payment. |
| `CANCELLATION_PENDING` | Show cancellation processing; do not repeatedly POST before backoff is due. |
| `REFUND_PENDING` | Show refund processing. |
| `MANUAL_REVIEW` | Show support-required state and disable normal mutations. |
| `COMPLETED` | Show delivered. |
| `CANCELLED` | Show cancelled with no captured funds outstanding. |
| `REFUNDED` | Show refund completed. |

### Frontend Security Rules

- Use `cache: "no-store"` for creation, checkout, invitation, OTP, recovery, and reissue requests.
- Set the invitation page's referrer policy to `no-referrer`.
- Redact full invitation URLs, OTPs, access tokens, checkout credentials, payment references, party contact details, and provider failure messages from analytics and session replay.
- Preserve each idempotency key until its mutation has an explicit terminal response. A different key while checkout is still open returns `400`.
- Treat Paystack redirects as navigation only. Payment is confirmed solely by refreshed backend state.
- Never call Paystack, Terminal, or Chowdeck directly from the frontend and never simulate webhook success.

### V16 Changed DTOs

```ts
export interface OrderPartyRequest {
  firstName: string;
  lastName: string;
  email: string;
  phoneNumber: string;
  address: OrderAddressRequest;
}

export interface CreateOrderRequest {
  amount: number;
  itemDescription: string;
  creatorRole: OrderPartyRole;
  creatorAddress: OrderAddressRequest;
  counterparty: OrderPartyRequest;
  parcel: OrderParcelRequest;
  vendorBankAccount: ResolveBankAccountRequest;
}

export interface CreateOrderResponse {
  id: number;
  itemAmount: number;
  currency: string;
  itemDescription: string;
  creatorRole: OrderPartyRole;
  counterpartyRole: OrderPartyRole;
  fundingMode: OrderFundingMode;
  escrowReleasePolicy: EscrowReleasePolicy;
  status: OrderStatus;
  createdAt: string;
}

export interface ShippingQuoteResponse {
  quoteId: number;
  carrierName: string;
  carrierLogo: string | null;
  serviceDescription: string | null;
  deliveryFee: number;
  serviceFee: number;
  totalAmount: number;
  currency: string;
  pickupEstimate: string | null;
  deliveryEstimate: string | null;
  expiresAt: string;
}

export interface ShippingSelectionResponse {
  status: OrderStatus;
  fundingMode: OrderFundingMode;
  selectedQuote: ShippingQuoteResponse;
  pricing: PricingBreakdownResponse;
  paymentInstructions: PaymentInstructionsResponse | null;
  buyerPaymentLink: string | null;
}

export interface VendorPayoutAccountResponse {
  bankAccountLast4: string;
  bankAccountName: string;
  bankName: string;
}

export interface CreateWaybillRequest {
  creatorRole: WaybillPartyRole;
  payerRole: WaybillPartyRole;
  description: string;
  declaredValue: number;
  creatorAddress: OrderAddressRequest;
  parcel: OrderParcelRequest;
  oppositeParty: WaybillPartyRequest;
}

export interface CreateWaybillResponse {
  details: WaybillDetailsResponse;
  paymentLink: string | null;
  expiresAt: string | null;
  replayed: boolean;
}

export interface WaybillQuoteSelectionResponse {
  selectedQuote: WaybillQuoteResponse;
  paymentLink: string | null;
  paymentLinkExpiresAt: string | null;
}
```

`WaybillDetailsResponse`, `PublicWaybillInvitationResponse`, and `WaybillCheckoutResponse` use `payerRole`, not `payerType`. Admin DTOs intentionally retain internal `payerType`. New public waybill summaries always use `invitationKind: "PAYMENT_ONLY"`, `targetPartyRole: payerRole`, and `detailsRequired: false`.

Removed public routes:

```text
POST /orders/token/{shareToken}/bank-account/resolve
POST /orders/token/{shareToken}/counterparty-info
POST /orders/token/{shareToken}/shipping/quotes
PUT  /orders/token/{shareToken}/shipping/selection
POST /waybills/invitations/{token}/party-details
POST /waybills/invitations/{token}/payer-claim
POST /waybills/invitations/{token}/shipping/quotes
PUT  /waybills/invitations/{token}/shipping/selection
```

Added payment-access routes:

```text
POST /orders/token/{shareToken}/payment-access-otp
POST /waybills/invitations/{token}/payment-access-otp
```

### Nigerian Location Selectors

Use the backend catalogue for every order and waybill address form:

1. Call public `GET /api/locations/states` and render each `name` while retaining its `code` as the selector value.
2. After state selection, clear any previous city and call public `GET /api/locations/states/{stateCode}/cities` with that code.
3. Render and submit the selected city `name`.
4. Submit the selected state `name`, not its code, in the existing address `state` field. Continue submitting `country: "NG"`.

Both responses are bare arrays and carry `Cache-Control: max-age=3600, public`; normal browser caching is safe. The backend shares one Nigeria-wide provider snapshot for all users and refreshes it after 24 hours, so frontend code must not persist or expose Terminal credentials. An unsupported state code returns `400`; a first-load provider outage returns `503`, so show retryable loading copy rather than treating it as invalid input.

## Local Setup

Frontend origin:

```text
http://localhost:3000
```

Backend API origin:

```text
http://localhost:8080
```

Do not append `/api` globally. Authentication, user, bank, and wallet routes begin with `/api`; order routes begin with `/orders`.

The local backend `.env` should contain:

```text
SPRING_PROFILES_ACTIVE=local
APP_BASE_URL=http://localhost:8080
FRONTEND_BASE_URL=http://localhost:3000
```

For waybills, `FRONTEND_BASE_URL` creates invitation links and `APP_BASE_URL` creates the Paystack callback URL. The invitation link is a frontend route; API calls still use the backend origin.

Local provider behavior:

- Terminal Africa is used only when `TERMINAL_AFRICA_ENABLED=true`; adding a key alone does not enable it.
- Chowdeck is unavailable while `CHOWDECK_HARD_DISABLED=true`, regardless of the database mode. Local environments should keep this true unless deliberately testing Relay against `https://api.chowdeck.com`.
- When Terminal is disabled, the backend uses the in-memory shipping stub.
- When Twilio is disabled, the SMS stub discards OTP messages.
- Email still uses Resend.
- Registration, virtual accounts, banks, and payout-account resolution still use Paystack.
- DVA balance credits and guest checkout payments require a signed Paystack webhook.

Start the backend with exactly one allowed profile:

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

Missing, unknown, or combined profiles are rejected.

## Global HTTP Rules

Member request:

```http
Authorization: Bearer <jwt>
Content-Type: application/json
```

Verified guest request:

```http
X-Order-Access-Token: <guestAccessToken>
Content-Type: application/json
```

Verified waybill invitation request:

```http
X-Waybill-Access-Token: <waybillAccessToken>
Content-Type: application/json
```

Wallet payment, legacy delivery confirmation, waybill creation, waybill checkout, and waybill cancellation require an `Idempotency-Key` on their documented routes:

```http
Idempotency-Key: <stable-value-for-this-mutation-attempt>
```

The idempotency key can be any nonblank value up to 255 characters. A UUID is recommended. Reuse the same value after an ambiguous timeout. Legacy delivery confirmation always reuses one stable key; V19 carrier-pickup settlement is server-initiated and needs no frontend key. A new intentional wallet-payment attempt after an explicit insufficient response should use a new key.

Serialization rules:

- Money is returned as decimal JSON numbers.
- Do not calculate money with JavaScript binary floating point.
- IDs are JSON integers.
- Enum values are uppercase and case-sensitive.
- `Instant` values are ISO-8601 timestamps, normally ending in `Z`.
- `LocalDateTime` fields such as order `createdAt` have no offset.
- Nullable response fields are normally included as `null`.
- `GET /orders`, `GET /waybills`, `GET /api/wallet/transactions`, `GET /api/wallet/withdrawals`, and every admin list route return page envelopes. Other collection routes return bare arrays.
- Paginated routes accept `page` (default `0`) and `size` (default `20`, maximum `100`). Invalid values return `400`.

## CORS And Rate Limits

Allowed browser origins:

```text
http://localhost:3000
https://shopsecure.app
https://www.shopsecure.app
```

Not currently allowed:

```text
http://127.0.0.1:3000
http://localhost:5173
https://admin.shopsecure.app
```

An admin UI must currently run on one of the allowed origins, for example under `/admin` on `https://shopsecure.app`. A separately hosted admin origin or a Vite frontend on port 5173 requires a backend CORS change before browser requests will work.

Allowed methods are `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, and `OPTIONS`. All request headers are allowed and credentials are enabled.

Rate limits apply to frontend routes:

- Per member or source IP: burst 10, then approximately 2 requests per second.
- Global: burst 100, then approximately 50 requests per second.
- Signed Paystack and Terminal webhooks are exempt. The unsigned Chowdeck callback is rate-limited by source IP.

A rate-limit response is `429` with a nonstandard body:

```json
{
  "error": "Too many requests. Please slow down."
}
```

The backend sets `Retry-After`, but CORS does not currently expose that response header. Use it when readable; otherwise use a jittered 3 to 5 second fallback.

## API Enums

```ts
export type MemberRole = "USER" | "ADMIN";
export type OrderPartyRole = "BUYER" | "VENDOR";
export type OrderFundingMode =
  | "WALLET"
  | "PAYSTACK_CHECKOUT"
  | "DIRECT_DVA"; // migrated orders only
export type WalletPaymentOutcome = "FUNDED" | "INSUFFICIENT_FUNDS";
export type EscrowReleasePolicy =
  | "BUYER_DELIVERY_CONFIRMATION"
  | "CARRIER_PICKUP";

export type OrderStatus =
  | "AWAITING_COUNTERPARTY"
  | "AWAITING_SHIPPING_RATE"
  | "AWAITING_PAYMENT"
  | "SHIPMENT_BOOKING"
  | "READY_FOR_DELIVERY"
  | "PAYOUT_PENDING"
  | "IN_TRANSIT"
  | "COMPLETED"
  | "PAYMENT_FAILED"
  | "SHIPPING_FAILED"
  | "PAYOUT_FAILED"
  | "CANCELLED"
  | "MANUAL_REVIEW";

export type PaymentStatus =
  | "NOT_STARTED"
  | "PENDING"
  | "CONFIRMED"
  | "UNDERPAID"
  | "OVERPAID"
  | "FAILED";

export type SettlementStatus =
  | "NOT_STARTED"
  | "PENDING"
  | "DISPATCH_UNKNOWN"
  | "COMPLETED"
  | "FAILED"
  | "REVERSED"
  | "REQUIRES_ACTION";

export type ShipmentStatus =
  | "DRAFT"
  | "CONFIRMED"
  | "PENDING"
  | "IN_TRANSIT"
  | "DELIVERED"
  | "CANCELLED"
  | "FAILED"
  | "UNKNOWN";

export type WalletTransactionType =
  | "LEGACY_ESCROW_DEBIT"
  | "ORDER_RELEASE"
  | "DVA_CREDIT"
  | "ORDER_PAYMENT_DEBIT"
  | "WITHDRAWAL_DEBIT"
  | "WITHDRAWAL_REFUND";

export type WalletWithdrawalStatus =
  | "PENDING"
  | "DISPATCH_UNKNOWN"
  | "COMPLETED"
  | "FAILED"
  | "REVERSED"
  | "REQUIRES_ACTION";

export type SettlementType = "EXTERNAL_PAYOUT" | "WALLET_CREDIT";
export type SettlementTrigger =
  | "BUYER_DELIVERY_CONFIRMATION"
  | "CARRIER_PICKUP";

export type PaymentAttemptStatus =
  | "INITIALIZED"
  | "SUCCESS"
  | "FAILED"
  | "ABANDONED"
  | "SUPERSEDED";

export type PaymentReceiptClassification =
  | "MATCHED"
  | "UNDERPAID"
  | "OVERPAID"
  | "LATE"
  | "DUPLICATE"
  | "WALLET_CREDITED"
  | "UNMATCHED"
  | "FAILED";

export type ProviderPaymentStatus =
  | "PENDING"
  | "SUCCESS"
  | "FAILED"
  | "ABANDONED"
  | "UNKNOWN";

export type ShippingQuoteStatus =
  | "AVAILABLE"
  | "SELECTED"
  | "USED"
  | "EXPIRED";

export type WaybillPartyRole = "SENDER" | "RECIPIENT";
export type WaybillPayerType = "CREATOR" | "INVITED_PAYER";
export type WaybillInvitationKind = "PARTY_COMPLETION" | "PAYMENT_ONLY";
export type WaybillPartySource =
  | "ACCOUNT_SNAPSHOT"
  | "CREATOR_PROVIDED"
  | "INVITEE_PROVIDED";

export type WaybillStatus =
  | "AWAITING_PARTY_DETAILS"
  | "AWAITING_QUOTE"
  | "AWAITING_PAYMENT"
  | "PAYMENT_REVIEW"
  | "SHIPMENT_BOOKING"
  | "READY_FOR_DELIVERY"
  | "SHIPPING_FAILED"
  | "CANCELLATION_PENDING"
  | "REFUND_PENDING"
  | "COMPLETED"
  | "CANCELLED"
  | "REFUNDED"
  | "MANUAL_REVIEW";

export type WaybillPaymentStatus =
  | "NOT_STARTED"
  | "PENDING"
  | "CONFIRMED"
  | "REFUND_PENDING"
  | "REFUNDED"
  | "REVIEW";

export type WaybillPaymentAttemptStatus =
  | "INITIALIZING"
  | "INITIALIZATION_UNKNOWN"
  | "INITIALIZED"
  | "SUCCESS"
  | "FAILED"
  | "ABANDONED"
  | "SUPERSEDED";

export type WaybillPaymentClassification =
  | "MATCHED"
  | "UNDERPAID"
  | "OVERPAID"
  | "DUPLICATE"
  | "LATE"
  | "FAILED";

export type WaybillRefundStatus =
  | "REQUESTED"
  | "DISPATCHING"
  | "DISPATCH_UNKNOWN"
  | "PENDING"
  | "PROCESSING"
  | "PROCESSED"
  | "FAILED"
  | "MANUAL_REVIEW";
```

`PARTY_COMPLETION`, `INVITEE_PROVIDED`, and `AWAITING_PARTY_DETAILS` remain in response typing for historical rows. Current selection and reissue emit only `PAYMENT_ONLY` invitations.

## Shared TypeScript Contract

### Requests

```ts
export interface RegisterRequest {
  firstname: string;
  lastname: string;
  email: string;
  phoneNumber: string;
  password: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface VerifyEmailRequest {
  email: string;
  challengeId: string;
  code: string;
}

export interface ResetPasswordRequest {
  email: string;
  code: string;
  newPassword: string;
}

export interface UpdateProfileRequest {
  firstname: string;
  lastname: string;
  phoneNumber: string;
}

export interface OrderAddressRequest {
  line1: string;
  line2?: string | null;
  city: string;
  state: string;
  country: string;
  postalCode: string;
}

export interface ShippingStateResponse {
  code: string;
  name: string;
}

export interface ShippingCityResponse {
  name: string;
  stateCode: string;
}

export interface OrderParcelRequest {
  weightKg: number;
  lengthCm?: number | null;
  widthCm?: number | null;
  heightCm?: number | null;
}

export interface CreateOrderRequest {
  amount: number;
  itemDescription: string;
  creatorRole: OrderPartyRole;
  creatorAddress: OrderAddressRequest;
  counterparty: OrderPartyRequest;
  parcel: OrderParcelRequest;
  vendorBankAccount: ResolveBankAccountRequest;
}

export interface ResolveBankAccountRequest {
  accountNumber: string;
  bankCode: string;
}

/** Historical only; no V16 controller accepts this request. */
export interface CounterpartyInfoRequest {
  firstName: string;
  lastName: string;
  email: string;
  phoneNumber: string;
  address: OrderAddressRequest;
  externalBankAccount?: ResolveBankAccountRequest;
  parcel?: OrderParcelRequest;
}

export interface OtpVerificationRequest {
  challengeId: string;
  code: string;
}

export interface ResendOtpRequest {
  challengeId: string;
}

export interface SelectShippingQuoteRequest {
  quoteId: number;
}

export interface ConfirmDeliveryRequest {
  confirmation: true;
}
```

### Responses

```ts
export interface VirtualAccountDetailsResponse {
  accountNumber: string;
  bankName: string;
  accountName: string;
  expiresAt: string | null;
}

export interface AuthResponse {
  token: string;
  message: string;
  userId: number;
  firstname: string;
  lastname: string;
  email: string;
  phoneNumber: string | null;
  role: MemberRole;
  virtualAccount: VirtualAccountDetailsResponse | null;
}

export interface AuthChallengeResponse {
  challengeId: string;
  emailMasked: string;
  expiresInSeconds: number;
  message: string;
}

export interface MessageResponse {
  message: string;
}

export interface WalletResponse {
  balance: number;
  currency: string;
  updatedAt: string;
}

export interface CurrentUserResponse {
  userId: number;
  firstname: string;
  lastname: string;
  email: string;
  phoneNumber: string | null;
  role: MemberRole;
  wallet: WalletResponse | null;
  virtualAccount: VirtualAccountDetailsResponse | null;
}

export interface BankResponse {
  name: string;
  code: string;
}

export interface ResolvedBankAccountResponse {
  accountNumberMasked: string;
  accountName: string;
  bankCode: string;
  bankName: string;
}

export interface AddressResponse {
  line1: string;
  line2: string;
  city: string;
  state: string;
  country: string;
  postalCode: string;
}

export interface ParcelResponse {
  weightKg: number;
  lengthCm: number | null;
  widthCm: number | null;
  heightCm: number | null;
}

export interface CounterpartyResponse {
  firstName: string;
  lastName: string;
  email: string;
  phoneNumber: string;
  address: AddressResponse;
  bankAccountLast4: string | null;
  bankAccountName: string | null;
  bankName: string | null;
  phoneVerified: boolean;
}

export interface PricingBreakdownResponse {
  itemAmount: number;
  deliveryFee: number;
  serviceFee: number;
  totalAmount: number;
  currency: string;
}

export interface PaymentInstructionsResponse {
  virtualAccount: VirtualAccountDetailsResponse;
  amount: number;
  currency: string;
  paymentReference: string;
  narration: string;
}

export interface OrderWalletPaymentResponse {
  orderId: number;
  outcome: WalletPaymentOutcome;
  status: OrderStatus;
  paymentStatus: PaymentStatus;
  amountRequired: number;
  walletBalance: number;
  shortfall: number;
  currency: string;
  topUpInstructions: PaymentInstructionsResponse | null;
  fundedAt: string | null;
}

export interface OrderCheckoutResponse {
  orderId: number;
  fundingMode: "PAYSTACK_CHECKOUT";
  status: OrderStatus;
  paymentStatus: PaymentStatus;
  amount: number;
  currency: string;
  reference: string;
  authorizationUrl: string;
  accessCode: string;
  initializedAt: string;
}

export interface ShippingQuoteResponse {
  quoteId: number;
  carrierName: string;
  carrierLogo: string | null;
  serviceDescription: string | null;
  deliveryFee: number;
  serviceFee: number;
  totalAmount: number;
  currency: string;
  pickupEstimate: string | null;
  deliveryEstimate: string | null;
  expiresAt: string;
}

export interface ShippingSelectionResponse {
  status: OrderStatus;
  fundingMode: OrderFundingMode;
  selectedQuote: ShippingQuoteResponse;
  pricing: PricingBreakdownResponse;
  paymentInstructions: PaymentInstructionsResponse | null;
  buyerPaymentLink: string | null;
}

export interface ShippingStatusResponse {
  status: ShipmentStatus;
  carrierName: string | null;
  trackingReference: string | null;
  trackingUrl: string | null;
  shopSecureTrackingUrl: string | null;
  trackingAvailableAt: string | null;
  deliveryEstimate: string | null;
  updatedAt: string;
}

export interface CreateOrderResponse {
  id: number;
  itemAmount: number;
  currency: string;
  itemDescription: string;
  creatorRole: OrderPartyRole;
  counterpartyRole: OrderPartyRole;
  fundingMode: OrderFundingMode;
  escrowReleasePolicy: EscrowReleasePolicy;
  status: OrderStatus;
  createdAt: string;
}

export interface OrderDetailsResponse {
  id: number;
  buyerPaymentLink: string | null;
  itemDescription: string;
  itemAmount: number;
  currency: string;
  creatorRole: OrderPartyRole;
  counterpartyRole: OrderPartyRole;
  fundingMode: OrderFundingMode;
  status: OrderStatus;
  paymentStatus: PaymentStatus;
  settlementStatus: SettlementStatus;
  escrowReleasePolicy: EscrowReleasePolicy;
  pricing: PricingBreakdownResponse | null;
  paymentInstructions: PaymentInstructionsResponse | null;
  counterparty: CounterpartyResponse | null;
  vendorPayoutAccount: VendorPayoutAccountResponse | null;
  parcel: ParcelResponse | null;
  shipping: ShippingStatusResponse | null;
  fundedAt: string | null;
  deliveryConfirmedAt: string | null;
  carrierPickupConfirmedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PublicOrderResponse {
  itemAmount: number;
  itemDescription: string;
  vendorName: string;
  currency: string;
  status: OrderStatus;
  creatorRole: OrderPartyRole;
  counterpartyRole: OrderPartyRole;
  counterpartyVerified: boolean;
  paymentStatus: PaymentStatus;
  settlementStatus: SettlementStatus;
  escrowReleasePolicy: EscrowReleasePolicy;
  pricing: PricingBreakdownResponse | null;
  selectedQuote: ShippingQuoteResponse | null;
}

export interface OtpChallengeResponse {
  challengeId: string;
  counterpartyRole: OrderPartyRole;
  phoneNumberMasked: string;
  expiresInSeconds: number;
}

export interface OrderVerificationResponse {
  status: OrderStatus;
  counterpartyRole: OrderPartyRole;
  phoneVerified: boolean;
  guestAccessToken: string;
  guestAccessTokenExpiresAt: string;
}

export interface GuestOrderStatusResponse {
  status: OrderStatus;
  fundingMode: OrderFundingMode;
  paymentStatus: PaymentStatus;
  settlementStatus: SettlementStatus;
  escrowReleasePolicy: EscrowReleasePolicy;
  amountExpected: number | null;
  amountReceived: number | null;
  currency: string;
  paymentInstructions: PaymentInstructionsResponse | null;
  shipping: ShippingStatusResponse | null;
  fundedAt: string | null;
  deliveryConfirmedAt: string | null;
  carrierPickupConfirmedAt: string | null;
  completedAt: string | null;
}

export interface DeliveryConfirmationResponse {
  status: OrderStatus;
  settlementStatus: SettlementStatus;
  deliveryConfirmedAt: string | null;
  completedAt: string | null;
}

export interface OrderStatusHistoryResponse {
  fromStatus: OrderStatus | null;
  toStatus: OrderStatus;
  reason: string;
  createdAt: string;
}

export interface WalletTransactionResponse {
  reference: string;
  type: WalletTransactionType;
  amount: number;
  balanceBefore: number;
  balanceAfter: number;
  orderId: number | null;
  createdAt: string;
}

export interface WithdrawalBankAccountResponse {
  accountNumberMasked: string;
  accountName: string;
  bankCode: string;
  bankName: string;
  updatedAt: string;
}

export interface WalletWithdrawalChallengeResponse {
  challengeId: string;
  phoneNumberMasked: string;
  expiresInSeconds: number;
}

export interface WalletWithdrawalResponse {
  withdrawalId: number;
  reference: string;
  amount: number;
  currency: string;
  status: WalletWithdrawalStatus;
  accountNumberMasked: string;
  accountName: string;
  bankName: string;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export type AdminMoneyByCurrency = {
  [currency: string]: number | undefined;
};

export interface AdminOrderValue {
  grossAmount: number;
  itemAmount: number;
  serviceFees: number;
  deliveryMarkup: number;
}

export type AdminOrderValueByCurrency = {
  [currency: string]: AdminOrderValue | undefined;
};

export interface AdminDashboardResponse {
  asOf: string;
  users: {
    total: number;
    verified: number;
    pendingVerification: number;
    admins: number;
  };
  orders: {
    total: number;
    byStatus: Partial<Record<OrderStatus, number>>;
    byPaymentStatus: Partial<Record<PaymentStatus, number>>;
    confirmedValueByCurrency: AdminOrderValueByCurrency;
  };
  finance: {
    availableWalletBalance: AdminMoneyByCurrency;
    outstandingWithdrawalAmount: AdminMoneyByCurrency;
    customerObligation: AdminMoneyByCurrency;
    unsettledOrderPrincipal: AdminMoneyByCurrency;
    paymentReceiptsRequiringReview: Partial<
      Record<PaymentReceiptClassification, AdminMoneyByCurrency>
    >;
  };
  operations: {
    withdrawalsByStatus: Partial<Record<WalletWithdrawalStatus, number>>;
    settlementsByStatus: Partial<Record<SettlementStatus, number>>;
    shipmentsByStatus: Partial<Record<ShipmentStatus, number>>;
    paymentReceiptsByClassification: Partial<
      Record<PaymentReceiptClassification, number>
    >;
    manualReviewOrders: number;
    withdrawalAttentionRequired: number;
    settlementAttentionRequired: number;
    shippingAttentionRequired: number;
    anomalousPaymentReceipts: number;
  };
}

export type ChowdeckMode = "OFF" | "SHADOW" | "ON";

export interface AdminChowdeckControlResponse {
  configuredMode: ChowdeckMode;
  effectiveMode: ChowdeckMode;
  hardKillActive: boolean;
  version: number;
  updatedByAdminId: number | null;
  updatedAt: string;
}

export interface UpdateChowdeckControlRequest {
  mode: ChowdeckMode;
  expectedVersion: number;
  reason: string;
}

export interface AdminChowdeckControlHistoryResponse {
  id: number;
  fromMode: ChowdeckMode;
  toMode: ChowdeckMode;
  reason: string;
  changedByAdminId: number;
  changedAt: string;
  controlVersion: number;
}

export interface AdminUserSummaryResponse {
  id: number;
  firstName: string | null;
  lastName: string | null;
  maskedEmail: string | null;
  maskedPhoneNumber: string | null;
  role: MemberRole;
  verified: boolean;
  emailVerifiedAt: string | null;
  createdAt: string | null;
  walletBalance: number | null;
  walletCurrency: string | null;
}

export interface AdminVirtualAccountSnapshot {
  maskedAccountNumber: string | null;
  bankName: string | null;
  accountName: string | null;
  expiresAt: string | null;
}

export interface AdminBankAccountSnapshot {
  maskedAccountNumber: string | null;
  bankCode: string | null;
  bankName: string | null;
  accountName: string | null;
  updatedAt: string | null;
}

export interface AdminUserDetailResponse {
  user: AdminUserSummaryResponse;
  virtualAccount: AdminVirtualAccountSnapshot | null;
  withdrawalBankAccount: AdminBankAccountSnapshot | null;
  orderCount: number;
  withdrawalCount: number;
}

export interface AdminOrderSummaryResponse {
  id: number;
  creatorId: number;
  creatorName: string;
  maskedCreatorEmail: string | null;
  maskedCreatorPhoneNumber: string | null;
  itemDescription: string;
  creatorRole: OrderPartyRole;
  fundingMode: OrderFundingMode;
  status: OrderStatus;
  paymentStatus: PaymentStatus;
  settlementStatus: SettlementStatus;
  escrowReleasePolicy: EscrowReleasePolicy;
  itemAmount: number;
  deliveryFee: number | null;
  serviceFee: number | null;
  totalAmount: number | null;
  currency: string;
  fundedAt: string | null;
  deliveryConfirmedAt: string | null;
  carrierPickupConfirmedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AdminWithdrawalResponse {
  id: number;
  userId: number;
  maskedUserEmail: string | null;
  amount: number;
  currency: string;
  status: WalletWithdrawalStatus;
  reference: string;
  transferId: string | null;
  maskedAccountNumber: string | null;
  bankName: string | null;
  accountName: string | null;
  dispatchAttempt: number;
  failureReason: string | null;
  providerUpdatedAt: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface AdminSettlementResponse {
  id: number;
  orderId: number;
  settlementType: SettlementType;
  amount: number;
  currency: string;
  status: SettlementStatus;
  trigger: SettlementTrigger;
  triggeredAt: string;
  payoutReference: string;
  transferId: string | null;
  unexpectedPayoutReference: string | null;
  unexpectedTransferId: string | null;
  payoutAttempt: number;
  failureReason: string | null;
  providerUpdatedAt: string | null;
  nextReconciliationAt: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface AdminShipmentResponse {
  id: number;
  orderId: number;
  provider: string;
  providerShipmentId: string | null;
  status: ShipmentStatus;
  trackingReference: string | null;
  bookingAttempt: number;
  nextBookingAttemptAt: string | null;
  failureReason: string | null;
  providerUpdatedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AdminWalletTransactionResponse {
  id: number;
  userId: number;
  maskedUserEmail: string | null;
  orderId: number | null;
  withdrawalId: number | null;
  paymentReceiptId: number | null;
  transactionType: WalletTransactionType;
  reference: string;
  amount: number;
  balanceBefore: number;
  balanceAfter: number;
  createdAt: string;
}

export interface AdminPaymentAttemptResponse {
  id: number;
  orderId: number;
  provider: string;
  providerReference: string;
  amount: number;
  currency: string;
  status: PaymentAttemptStatus;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface AdminPaymentReceiptResponse {
  id: number;
  orderId: number | null;
  providerEventKey: string;
  providerReference: string;
  maskedReceiverAccountNumber: string | null;
  amount: number | null;
  currency: string | null;
  channel: string | null;
  providerStatus: ProviderPaymentStatus;
  classification: PaymentReceiptClassification;
  occurredAt: string | null;
  createdAt: string;
}

export interface AdminPartySnapshot {
  firstName: string;
  lastName: string;
  maskedEmail: string | null;
  maskedPhoneNumber: string | null;
  city: string;
  state: string;
  country: string;
  verified: boolean;
  maskedBankAccountNumber: string | null;
  bankName: string | null;
  bankAccountName: string | null;
}

export interface AdminParcelSnapshot {
  weightKg: number;
  lengthCm: number | null;
  widthCm: number | null;
  heightCm: number | null;
  quantity: number;
  parcelType: string;
}

export interface AdminShippingQuoteSnapshot {
  id: number;
  carrierName: string;
  serviceDescription: string | null;
  providerAmount: number;
  deliveryMarkup: number;
  deliveryFee: number;
  serviceFee: number;
  buyerTotal: number;
  currency: string;
  pickupEstimate: string | null;
  deliveryEstimate: string | null;
  status: ShippingQuoteStatus;
  selectedAt: string | null;
  expiresAt: string;
}

export interface AdminOrderDetailResponse {
  order: AdminOrderSummaryResponse;
  creator: AdminPartySnapshot;
  counterparty: AdminPartySnapshot | null;
  parcel: AdminParcelSnapshot | null;
  shipment: AdminShipmentResponse | null;
  selectedShippingQuote: AdminShippingQuoteSnapshot | null;
  settlement: AdminSettlementResponse | null;
  paymentAttempts: AdminPaymentAttemptResponse[];
  paymentReceipts: AdminPaymentReceiptResponse[];
  history: OrderStatusHistoryResponse[];
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}
```

### Exact Waybill DTOs

These interfaces mirror the V16 request and response records. Waybill timestamps are UTC `LocalDateTime` strings without an offset suffix; parse them as UTC wall time rather than browser-local time.

```ts
export interface WaybillPartyRequest {
  firstName: string;
  lastName: string;
  email: string;
  phoneNumber: string;
  address: OrderAddressRequest;
}

/** Historical only; no V16 controller accepts this request. */
export interface WaybillPayerClaimRequest {
  firstName: string;
  lastName: string;
  email: string;
  phoneNumber: string;
}

export interface CreateWaybillRequest {
  creatorRole: WaybillPartyRole;
  payerRole: WaybillPartyRole;
  description: string;
  declaredValue: number;
  creatorAddress: OrderAddressRequest;
  parcel: OrderParcelRequest;
  oppositeParty: WaybillPartyRequest;
}

export interface SelectWaybillQuoteRequest {
  quoteId: number;
}

export interface VerifyWaybillOtpRequest {
  challengeId: string;
  code: string;
}

export interface ResendWaybillOtpRequest {
  challengeId: string;
}

export interface CancelWaybillRequest {
  reason?: string | null;
}

export interface WaybillAddressResponse {
  line1: string;
  line2: string | null;
  city: string;
  state: string;
  country: string;
  postalCode: string;
}

export interface WaybillPricingResponse {
  deliveryFee: number;
  serviceFee: number;
  totalAmount: number;
  currency: string;
}

export interface WaybillPartyResponse {
  role: WaybillPartyRole;
  source: WaybillPartySource;
  firstName: string;
  lastName: string;
  email: string;
  phoneNumber: string;
  address: WaybillAddressResponse;
  accountLinked: boolean;
  detailsCompletedAt: string | null;
  contactVerifiedAt: string | null;
}

export interface WaybillPublicPartyResponse {
  role: WaybillPartyRole;
  nameMasked: string;
  emailMasked: string | null;
  phoneNumberMasked: string | null;
  city: string;
  state: string;
  country: string;
}

export interface WaybillParcelResponse {
  weightKg: number;
  lengthCm: number | null;
  widthCm: number | null;
  heightCm: number | null;
  quantity: number;
  parcelType: string;
}

export interface WaybillQuoteResponse {
  quoteId: number;
  carrierName: string;
  carrierLogo: string | null;
  serviceDescription: string | null;
  deliveryFee: number;
  serviceFee: number;
  totalAmount: number;
  currency: string;
  pickupEstimate: string | null;
  deliveryEstimate: string | null;
  status: ShippingQuoteStatus;
  expiresAt: string;
}

export interface WaybillShipmentResponse {
  provider: string;
  providerShipmentId: string | null;
  status: ShipmentStatus;
  trackingReference: string | null;
  trackingUrl: string | null;
  failureReason: string | null;
  providerUpdatedAt: string | null;
  updatedAt: string;
}

export interface WaybillInvitationSummaryResponse {
  invitationId: number;
  kind: WaybillInvitationKind;
  targetPartyRole: WaybillPartyRole | null;
  canPay: boolean;
  stagedFirstName: string | null;
  stagedLastName: string | null;
  stagedEmail: string | null;
  stagedPhoneNumber: string | null;
  active: boolean;
  expiresAt: string;
  verifiedAt: string | null;
  detailsCompletedAt: string | null;
  paymentCompletedAt: string | null;
  revokedAt: string | null;
  createdAt: string;
}

export interface WaybillDetailsResponse {
  publicId: string;
  creatorRole: WaybillPartyRole;
  payerRole: WaybillPartyRole;
  description: string;
  declaredValue: number;
  currency: string;
  status: WaybillStatus;
  paymentStatus: WaybillPaymentStatus;
  pricing: WaybillPricingResponse | null;
  parties: WaybillPartyResponse[];
  parcel: WaybillParcelResponse | null;
  selectedQuote: WaybillQuoteResponse | null;
  shipment: WaybillShipmentResponse | null;
  invitations: WaybillInvitationSummaryResponse[];
  paymentDueAt: string | null;
  fundedAt: string | null;
  completedAt: string | null;
  cancellationReason: string | null;
  cancellationRequestedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateWaybillResponse {
  details: WaybillDetailsResponse;
  paymentLink: string | null;
  expiresAt: string | null;
  replayed: boolean;
}

export interface PublicWaybillInvitationResponse {
  waybillPublicId: string;
  invitationKind: WaybillInvitationKind;
  targetPartyRole: WaybillPartyRole | null;
  creatorRole: WaybillPartyRole;
  payerRole: WaybillPartyRole;
  description: string;
  declaredValue: number;
  currency: string;
  status: WaybillStatus;
  paymentStatus: WaybillPaymentStatus;
  amountDue: number | null;
  canPay: boolean;
  detailsRequired: boolean;
  inviteeEmailMasked: string | null;
  inviteePhoneNumberMasked: string | null;
  expiresAt: string;
}

export interface WaybillOtpChallengeResponse {
  challengeId: string;
  partyRole: WaybillPartyRole;
  emailMasked: string;
  phoneNumberMasked: string;
  expiresInSeconds: number;
}

export interface WaybillAccessResponse {
  waybillPublicId: string;
  accessToken: string | null;
  invitationKind: WaybillInvitationKind;
  targetPartyRole: WaybillPartyRole | null;
  canCompletePartyDetails: boolean;
  canPay: boolean;
  detailsCompleted: boolean;
  paymentCompleted: boolean;
  status: WaybillStatus;
  paymentStatus: WaybillPaymentStatus;
  description: string;
  declaredValue: number;
  currency: string;
  pricing: WaybillPricingResponse | null;
  parties: WaybillPublicPartyResponse[];
  parcel: WaybillParcelResponse | null;
  selectedQuote: WaybillQuoteResponse | null;
  shipment: WaybillShipmentResponse | null;
  accessExpiresAt: string;
}

export interface WaybillInvitationReissueResponse {
  paymentLink: string;
  expiresAt: string;
}

export interface WaybillQuoteSelectionResponse {
  selectedQuote: WaybillQuoteResponse;
  paymentLink: string | null;
  paymentLinkExpiresAt: string | null;
}

export interface WaybillCheckoutResponse {
  waybillPublicId: string;
  paymentAttemptId: number;
  payerRole: WaybillPartyRole;
  status: WaybillStatus;
  paymentStatus: WaybillPaymentStatus;
  attemptStatus: WaybillPaymentAttemptStatus;
  amount: number;
  currency: string;
  reference: string;
  authorizationUrl: string | null;
  accessCode: string | null;
  createdAt: string;
}

export interface WaybillRefundResponse {
  refundId: number;
  providerRefundId: string | null;
  providerRefundReference: string | null;
  amount: number;
  currency: string;
  status: WaybillRefundStatus;
  reason: string | null;
  failureReason: string | null;
  providerCreatedAt: string | null;
  providerUpdatedAt: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface WaybillCancellationResponse {
  waybillPublicId: string;
  status: WaybillStatus;
  paymentStatus: WaybillPaymentStatus;
  reason: string | null;
  cancellationRequestedAt: string;
  refund: WaybillRefundResponse | null;
}

export interface WaybillStatusHistoryResponse {
  fromStatus: WaybillStatus | null;
  toStatus: WaybillStatus;
  reason: string;
  createdAt: string;
}

export interface AdminWaybillSummaryResponse {
  id: number;
  publicId: string;
  creatorId: number;
  creatorName: string;
  maskedCreatorEmail: string | null;
  maskedCreatorPhoneNumber: string | null;
  creatorRole: WaybillPartyRole;
  payerType: WaybillPayerType;
  description: string;
  declaredValue: number;
  currency: string;
  status: WaybillStatus;
  paymentStatus: WaybillPaymentStatus;
  providerDeliveryFee: number | null;
  deliveryMarkup: number | null;
  deliveryFee: number | null;
  serviceFee: number | null;
  totalAmount: number | null;
  paymentDueAt: string | null;
  fundingPaymentReference: string | null;
  fundedAt: string | null;
  completedAt: string | null;
  cancellationReason: string | null;
  cancellationRequestedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AdminWaybillDetailResponse {
  waybill: AdminWaybillSummaryResponse;
  creator: {
    id: number;
    firstName: string | null;
    lastName: string | null;
    maskedEmail: string | null;
    maskedPhoneNumber: string | null;
    enabled: boolean;
    emailVerifiedAt: string | null;
    createdAt: string | null;
  };
  parties: Array<{
    id: number;
    userId: number | null;
    role: WaybillPartyRole;
    source: WaybillPartySource;
    firstName: string;
    lastName: string;
    maskedEmail: string | null;
    maskedPhoneNumber: string | null;
    city: string;
    state: string;
    country: string;
    detailsCompletedAt: string | null;
    contactVerifiedAt: string | null;
    createdAt: string;
    updatedAt: string;
  }>;
  parcel: {
    id: number;
    weightKg: number;
    lengthCm: number | null;
    widthCm: number | null;
    heightCm: number | null;
    quantity: number;
    parcelType: string;
    createdAt: string;
    updatedAt: string;
  } | null;
  shipment: {
    id: number;
    provider: string;
    providerShipmentId: string | null;
    status: ShipmentStatus;
    trackingReference: string | null;
    bookingAttempt: number;
    nextBookingAttemptAt: string | null;
    failureReason: string | null;
    cancellationAttempt: number;
    nextCancellationAttemptAt: string | null;
    cancellationFailureReason: string | null;
    providerUpdatedAt: string | null;
    createdAt: string;
    updatedAt: string;
  } | null;
  quotes: Array<{
    id: number;
    carrierName: string;
    serviceDescription: string | null;
    providerAmount: number;
    deliveryMarkup: number;
    deliveryFee: number;
    serviceFee: number;
    totalAmount: number;
    currency: string;
    pickupEstimate: string | null;
    deliveryEstimate: string | null;
    status: ShippingQuoteStatus;
    selectedAt: string | null;
    expiresAt: string;
    createdAt: string;
  }>;
  paymentAttempts: Array<{
    id: number;
    invitationId: number | null;
    payerType: WaybillPayerType;
    provider: string;
    reference: string;
    maskedCustomerEmail: string | null;
    amount: number;
    currency: string;
    status: WaybillPaymentAttemptStatus;
    providerTransactionId: string | null;
    nextRecheckAt: string | null;
    failureReason: string | null;
    createdAt: string;
    updatedAt: string;
    completedAt: string | null;
  }>;
  paymentReceipts: Array<{
    id: number;
    paymentAttemptId: number;
    providerEventKey: string;
    reference: string;
    amount: number | null;
    currency: string | null;
    channel: string | null;
    providerStatus: ProviderPaymentStatus;
    classification: WaybillPaymentClassification;
    occurredAt: string | null;
    createdAt: string;
  }>;
  refunds: Array<{
    id: number;
    paymentAttemptId: number;
    providerRefundId: string | null;
    providerRefundReference: string | null;
    amount: number;
    currency: string;
    status: WaybillRefundStatus;
    reason: string | null;
    failureReason: string | null;
    providerCreatedAt: string | null;
    providerUpdatedAt: string | null;
    createdAt: string;
    updatedAt: string;
    completedAt: string | null;
  }>;
  invitations: Array<{
    id: number;
    kind: WaybillInvitationKind;
    targetPartyRole: WaybillPartyRole | null;
    canPay: boolean;
    stagedFirstName: string | null;
    stagedLastName: string | null;
    maskedStagedEmail: string | null;
    maskedStagedPhoneNumber: string | null;
    otpSendCount: number;
    otpFailedAttemptCount: number;
    otpLockedAt: string | null;
    expiresAt: string;
    verifiedAt: string | null;
    detailsCompletedAt: string | null;
    paymentCompletedAt: string | null;
    revokedAt: string | null;
    createdAt: string;
    updatedAt: string;
  }>;
  history: WaybillStatusHistoryResponse[];
}
```

## Validation Rules

### Nigerian Phone Numbers

- All member and guest phone numbers must be Nigerian mobile numbers.
- Inputs may use `08051234567`, `2348051234567`, or `+2348051234567`.
- The backend canonicalizes stored and returned phone numbers to `+2348051234567`.
- Do not send `+080...` or `+234080...`; legacy values are repaired, but new UI should emit `+234...`.

### Registration

- `firstname` and `lastname`: required, maximum 100 characters.
- `email`: required and valid.
- `phoneNumber`: valid Nigerian mobile number as described above.
- `password` / `newPassword`: 8 to 72 characters with uppercase, lowercase, digit, and special character.

### Profile Update

- `firstname`, `lastname`, and a valid Nigerian `phoneNumber` are required.
- Email cannot be changed through profile update.

### Address

- `line1`: required, maximum 255.
- `line2`: optional, maximum 255.
- `city` and `state`: required, maximum 100.
- `country`: backend matching is case-insensitive for the two-letter value `NG`, but the frontend should always emit uppercase `NG`; other countries and `Nigeria` are rejected.
- `postalCode`: required, maximum 32.
- Populate selectors from `/api/locations/states` and `/api/locations/states/{stateCode}/cities`. The state code selects a city list; submit the selected state and city names in the address object.

### Parcel

- `weightKg`: required, minimum `0.001`, up to 9 integer digits and 3 decimal places.
- Dimensions are optional.
- Supplied dimensions must be at least `0.01` and have at most 9 integer digits and 2 decimal places.

### Order

- `amount` is item price only, minimum `0.01`, maximum 2 decimal places.
- `itemDescription`: required, maximum 2,000 characters.
- Buyer creator must omit `parcel`.
- Vendor creator must provide `parcel`.

### Bank

- `accountNumber`: exactly 10 digits.
- `bankCode`: required, maximum 20 characters, and must be returned by `/api/banks`.
- Never ask a vendor to type account name. Resolve and display it for confirmation.
- With an `sk_test_` Paystack key, `/api/banks` intentionally returns only `Paystack Test Bank` (`001`). Use account number `0000000000`; real-bank resolution in Paystack test mode has a daily limit and is rejected locally. The backend handles Paystack's separate transfer-test bank code internally, so the frontend must continue submitting `001`. Live mode returns the real supported Nigerian banks.

### Guest Details

- Guest vendor must provide `externalBankAccount` and `parcel`.
- Guest buyer must omit `externalBankAccount` and `parcel`.
- Names are separate `firstName` and `lastName`; `fullName` is no longer accepted.

### OTP

- `challengeId`: required.
- `code`: exactly six digits.

### Waybill

- `creatorRole`: exactly `SENDER` or `RECIPIENT`; `INVITED_PAYER` requires `SENDER` because the creator supplies pickup details and the invitee completes recipient details.
- `payerRole`: exactly `SENDER` or `RECIPIENT`; `oppositeParty` is always required. Internal/admin `payerType` is derived and is not submitted by clients.
- `description`: required, maximum 2,000 characters.
- `declaredValue`: minimum `0.01`, maximum 36 integer digits and two decimal places, excluded from the checkout total.
- `creatorAddress`, `parcel`, `payerRole`, and `oppositeParty`: always required.
- Cancellation always sends a JSON object; `reason` is optional and at most 500 characters.

## Endpoint Summary

| Method | Endpoint | Access | Success | Response |
|---|---|---|---|---|
| `POST` | `/api/auth/register` | Public | `202` | `AuthChallengeResponse` |
| `POST` | `/api/auth/verify-email` | Public | `200` | `AuthResponse` |
| `POST` | `/api/auth/resend-verification` | Public | `200` | `AuthChallengeResponse` |
| `POST` | `/api/auth/login` | Public | `200` | `AuthResponse` |
| `POST` | `/api/auth/forgot-password` | Public | `202` | `MessageResponse` |
| `POST` | `/api/auth/reset-password` | Public | `200` | `MessageResponse` |
| `GET` | `/api/users/me` | Member | `200` | `CurrentUserResponse` |
| `PATCH` | `/api/users/me` | Member | `200` | `CurrentUserResponse` |
| `GET` | `/api/locations/states` | Public | `200` | `ShippingStateResponse[]` |
| `GET` | `/api/locations/states/{stateCode}/cities` | Public | `200` | `ShippingCityResponse[]` |
| `GET` | `/api/banks` | Public | `200` | `BankResponse[]` |
| `POST` | `/api/bank-accounts/resolve` | Member | `200` | `ResolvedBankAccountResponse` |
| `GET` | `/api/wallet` | Member | `200` | `WalletResponse` |
| `GET` | `/api/wallet/transactions?page=0&size=20` | Member | `200` | `PageResponse<WalletTransactionResponse>` |
| `PUT` | `/api/wallet/bank-account` | Member | `200` | `WithdrawalBankAccountResponse` |
| `GET` | `/api/wallet/bank-account` | Member | `200` | `WithdrawalBankAccountResponse` |
| `POST` | `/api/wallet/withdrawals/challenge` | Member | `200` | `WalletWithdrawalChallengeResponse` |
| `POST` | `/api/wallet/withdrawals` | Member | `200` | `WalletWithdrawalResponse` |
| `GET` | `/api/wallet/withdrawals?page=0&size=20` | Member | `200` | `PageResponse<WalletWithdrawalResponse>` |
| `POST` | `/orders` | Member | `201` | `CreateOrderResponse` |
| `GET` | `/orders?page=0&size=20` | Member | `200` | `PageResponse<OrderDetailsResponse>` |
| `GET` | `/orders/{orderId}` | Owner | `200` | `OrderDetailsResponse` |
| `GET` | `/orders/{orderId}/history` | Owner | `200` | `OrderStatusHistoryResponse[]` |
| `POST` | `/orders/{orderId}/shipping/quotes` | Order creator | `200` | `ShippingQuoteResponse[]` |
| `PUT` | `/orders/{orderId}/shipping/selection` | Order creator | `200` | `ShippingSelectionResponse` |
| `POST` | `/orders/{orderId}/payments/wallet` | Buyer creator | `200` | `OrderWalletPaymentResponse` |
| `GET` | `/orders/{orderId}/shipping/tracking` | Owner | `200` | `ShippingStatusResponse` |
| `POST` | `/orders/{orderId}/shipping/cancel` | Owner | `200` | `ShippingStatusResponse` or empty |
| `POST` | `/orders/{orderId}/confirm-delivery` | Authenticated buyer creator | `200` | `DeliveryConfirmationResponse` |
| `GET` | `/orders/seller/pending-amount` | Member seller | `200` | `SellerPendingAmountResponse` |
| `GET` | `/orders/tracking/{trackingToken}` | Public bearer URL; no OTP | `200` | `PublicOrderTrackingResponse` |
| `POST` | `/orders/tracking/{trackingToken}/confirm-delivery` | Public bearer URL; no JWT, guest token, or OTP | `200` | `DeliveryConfirmationResponse` |
| `GET` | `/orders/token/{shareToken}` | Public | `200` | `PublicOrderResponse` |
| `POST` | `/orders/token/{shareToken}/payment-access-otp` | Public buyer | `202` | `OtpChallengeResponse` |
| `POST` | `/orders/token/{shareToken}/verify-otp` | Public | `200` | `OrderVerificationResponse` |
| `POST` | `/orders/token/{shareToken}/resend-otp` | Public | `200` | `OtpChallengeResponse` |
| `POST` | `/orders/token/{shareToken}/access-recovery-otp` | Public | `200` | `OtpChallengeResponse` |
| `POST` | `/orders/token/{shareToken}/recover-access` | Public | `200` | `OrderVerificationResponse` |
| `POST` | `/orders/token/{shareToken}/payments/checkout` | Guest buyer | `200` | `OrderCheckoutResponse` |
| `GET` | `/orders/token/{shareToken}/status` | Verified guest | `200` | `GuestOrderStatusResponse` |
| `POST` | `/orders/token/{shareToken}/delivery-otp` | Legacy-policy guest buyer only | `200` | `OtpChallengeResponse` |
| `POST` | `/orders/token/{shareToken}/confirm-delivery` | Legacy-policy guest buyer only | `200` | `DeliveryConfirmationResponse` |
| `POST` | `/waybills` | Member creator | `201` | `CreateWaybillResponse` |
| `GET` | `/waybills?page=0&size=20` | Member creator | `200` | `PageResponse<WaybillDetailsResponse>` |
| `GET` | `/waybills/{publicId}` | Creator | `200` | `WaybillDetailsResponse` |
| `GET` | `/waybills/{publicId}/history` | Creator | `200` | `WaybillStatusHistoryResponse[]` |
| `POST` | `/waybills/{publicId}/invitations/reissue` | Creator | `200` | `WaybillInvitationReissueResponse` |
| `POST` | `/waybills/{publicId}/shipping/quotes` | Creator | `200` | `WaybillQuoteResponse[]` |
| `PUT` | `/waybills/{publicId}/shipping/selection` | Creator | `200` | `WaybillQuoteSelectionResponse` |
| `POST` | `/waybills/{publicId}/payments/checkout` | Creator payer | `200` | `WaybillCheckoutResponse` |
| `GET` | `/waybills/{publicId}/shipping/tracking` | Creator | `200` | `WaybillShipmentResponse` |
| `POST` | `/waybills/{publicId}/cancel` | Creator | `200` | `WaybillCancellationResponse` |
| `GET` | `/waybills/invitations/{invitationToken}` | Public | `200` | `PublicWaybillInvitationResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/payment-access-otp` | Public payer | `202` | `WaybillOtpChallengeResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/verify-otp` | Public | `200` | `WaybillAccessResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/resend-otp` | Public | `200` | `WaybillOtpChallengeResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/access-recovery` | Verified invitation | `202` | `WaybillOtpChallengeResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/access-recovery/verify` | Public | `200` | `WaybillAccessResponse` |
| `GET` | `/waybills/invitations/{invitationToken}/status` | Invitation access token | `200` | `WaybillAccessResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/payments/checkout` | Invited payer | `200` | `WaybillCheckoutResponse` |
| `GET` | `/api/admin/dashboard` | Admin | `200` | `AdminDashboardResponse` |
| `GET` | `/api/admin/users` | Admin | `200` | `PageResponse<AdminUserSummaryResponse>` |
| `GET` | `/api/admin/users/{userId}` | Admin | `200` | `AdminUserDetailResponse` |
| `GET` | `/api/admin/orders` | Admin | `200` | `PageResponse<AdminOrderSummaryResponse>` |
| `GET` | `/api/admin/orders/{orderId}` | Admin | `200` | `AdminOrderDetailResponse` |
| `GET` | `/api/admin/waybills` | Admin | `200` | `PageResponse<AdminWaybillSummaryResponse>` |
| `GET` | `/api/admin/waybills/{waybillId}` | Admin | `200` | `AdminWaybillDetailResponse` |
| `GET` | `/api/admin/wallet-transactions` | Admin | `200` | `PageResponse<AdminWalletTransactionResponse>` |
| `GET` | `/api/admin/withdrawals` | Admin | `200` | `PageResponse<AdminWithdrawalResponse>` |
| `GET` | `/api/admin/settlements` | Admin | `200` | `PageResponse<AdminSettlementResponse>` |
| `GET` | `/api/admin/shipments` | Admin | `200` | `PageResponse<AdminShipmentResponse>` |
| `GET` | `/api/admin/payment-attempts` | Admin | `200` | `PageResponse<AdminPaymentAttemptResponse>` |
| `GET` | `/api/admin/payment-receipts` | Admin | `200` | `PageResponse<AdminPaymentReceiptResponse>` |
| `GET` | `/api/admin/shipping/chowdeck-control` | Admin | `200` | `AdminChowdeckControlResponse` |
| `PUT` | `/api/admin/shipping/chowdeck-control` | Admin | `200` | `AdminChowdeckControlResponse` |
| `GET` | `/api/admin/shipping/chowdeck-control/history` | Admin | `200` | `PageResponse<AdminChowdeckControlHistoryResponse>` |
| `POST` | `/api/webhooks/paystack` | Backend only | `200` | Empty |
| `POST` | `/api/webhooks/terminal` | Backend only | `200` | Empty |
| `POST` | `/api/webhooks/chowdeck` | Backend only; unsigned reconciliation signal | `200` | Empty |

## Admin Dashboard Frontend

### Authentication And Routing

The admin frontend uses the normal `POST /api/auth/login` flow and the same member JWT. There is no separate admin login or admin token.

- On login or `GET /api/users/me`, permit the admin application only when `role === "ADMIN"`.
- Keep the client-side role check as a navigation guard only. The backend independently enforces `ADMIN` on every `/api/admin/**` route.
- Public registration always creates `USER`; do not add an admin-role selector or role-management UI.
- Admin promotion is a controlled backend/database operation. After promotion, the administrator must log in again because existing JWTs are invalidated.
- A valid `USER` JWT receives `403` from admin routes. Anonymous or invalid sessions can also receive `401` or `403` from the security layer.
- If an admin request returns `403`, check `/api/users/me` before clearing the session. A valid non-admin account should be sent to the normal application or a forbidden page, not logged out as though the token were corrupt.
- Admin operational-resource endpoints are read-only. The only mutation is `PUT /api/admin/shipping/chowdeck-control`; do not render refund, adjustment, retry, cancellation, status-edit, role-edit, suspension, or deletion controls.

Use the existing member request header:

```http
Authorization: Bearer <admin-jwt>
```

Every successful admin response includes:

```http
Cache-Control: no-store
Pragma: no-cache
```

Also request admin data with browser cache disabled. Do not store responses in local storage, IndexedDB, a service-worker cache, persisted query cache, analytics, session replay, or error-report payloads.

```ts
export async function adminGet<T>(
  apiOrigin: string,
  path: `/api/admin/${string}`,
  token: string,
  signal?: AbortSignal,
): Promise<T> {
  const response = await fetch(`${apiOrigin}${path}`, {
    method: "GET",
    headers: { Authorization: `Bearer ${token}` },
    cache: "no-store",
    signal,
  });

  if (!response.ok) {
    throw await readApiErrorDefensively(response);
  }

  return response.json() as Promise<T>;
}
```

`readApiErrorDefensively` is a frontend helper, not an exported backend function. It must tolerate the standard error shape, security-layer responses, and the nonstandard `429` body described in the Error Contract section.

### Recommended Screens

| Frontend screen | Primary API | Useful drill-down |
|---|---|---|
| Overview | `/api/admin/dashboard` | Link single-state metric cards to filtered lists; see the composite-metric note below |
| Users | `/api/admin/users` | `/api/admin/users/{userId}` |
| Orders | `/api/admin/orders` | `/api/admin/orders/{orderId}` |
| Waybills | `/api/admin/waybills` | `/api/admin/waybills/{waybillId}` |
| Wallet ledger | `/api/admin/wallet-transactions` | Link `userId` and `orderId` where present; show withdrawal/receipt IDs as references only |
| Withdrawals | `/api/admin/withdrawals` | Filter by user or one selected status |
| Settlements | `/api/admin/settlements` | Link each row to its order |
| Shipments | `/api/admin/shipments` | Link each row to its order |
| Payment attempts | `/api/admin/payment-attempts` | Filter by order or checkout status |
| Payment receipts | `/api/admin/payment-receipts` | Filter by order, classification, or provider status |
| Shipping providers | `/api/admin/shipping/chowdeck-control` | Show current mode, effective mode, hard-kill state, and immutable history |

There is no admin search endpoint spanning resources. Cross-resource navigation should use the IDs already returned in each response.

There are no withdrawal-detail or payment-receipt-detail routes and no `withdrawalId` or `paymentReceiptId` list filters. Composite dashboard counters such as `withdrawalAttentionRequired`, `settlementAttentionRequired`, `shippingAttentionRequired`, and `anomalousPaymentReceipts` combine several enum values, while list endpoints accept only one status or classification at a time. Open those cards to an unfiltered operations screen or present one tab/request per constituent value; do not invent comma-separated or repeated query parameters.

### Chowdeck Control

Load `GET /api/admin/shipping/chowdeck-control` before rendering the control. Display `configuredMode` and `effectiveMode` separately. If `hardKillActive` is true, clearly state that deployment configuration is forcing effective `OFF`; changing the database mode alone will not activate Chowdeck.

Changing mode requires an explicit reason and the version just read:

```ts
await fetch(`${apiOrigin}/api/admin/shipping/chowdeck-control`, {
  method: "PUT",
  headers: {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json",
  },
  cache: "no-store",
  body: JSON.stringify({
    mode: nextMode,
    expectedVersion: control.version,
    reason,
  } satisfies UpdateChowdeckControlRequest),
});
```

Require a nonblank reason of at most 500 characters, disable duplicate submissions, and show a high-signal confirmation before `ON`. On `409`, discard the attempted local update, fetch the control again, and ask the administrator to review the newer state. Do not automatically replay a stale mutation. `SHADOW` sends eligible fee requests but customers still receive Terminal quotes; `ON` permits real Relay booking. Existing shipments remain with their stored provider in every mode.

The database mode is not an immediate network circuit breaker: quote requests that already read the previous mode may finish. An operator must set the environment hard kill and restart/redeploy for a process-boundary provider stop.

Use `GET /api/admin/shipping/chowdeck-control/history?page=0&size=20` for the audit table. Never request, display, persist, or log a Chowdeck secret or provider payload; neither is part of the API contract.

### Dashboard

`GET /api/admin/dashboard`

The response is an aggregate snapshot identified by `asOf`. The backend caches it for 30 seconds, so do not poll more frequently than every 30 seconds. Show `asOf` near the refresh control and treat an immediate refresh as potentially returning the same snapshot.

Dashboard maps are sparse. Statuses and currencies with no rows are omitted rather than returned with zero values. Always render missing keys with `?? 0`, and build currency sections from the keys actually returned rather than assuming NGN is the only possible currency.

```ts
const manualReviewCount = dashboard.operations.manualReviewOrders;
const pendingWithdrawals =
  dashboard.operations.withdrawalsByStatus.PENDING ?? 0;
const ngnCustomerObligation =
  dashboard.finance.customerObligation.NGN ?? 0;
```

Metric meaning:

- `orders.confirmedValueByCurrency` includes only orders with `paymentStatus === "CONFIRMED"`.
- `grossAmount` is the confirmed buyer total; `itemAmount`, `serviceFees`, and `deliveryMarkup` are separate components.
- `availableWalletBalance` is current spendable member wallet balance.
- `outstandingWithdrawalAmount` includes `PENDING`, `DISPATCH_UNKNOWN`, and `REQUIRES_ACTION` withdrawals.
- `customerObligation` is available wallet balance plus outstanding withdrawal amount.
- `unsettledOrderPrincipal` is item principal on confirmed orders whose settlement is not `COMPLETED`.
- `paymentReceiptsRequiringReview` contains successful `UNDERPAID`, `OVERPAID`, `DUPLICATE`, and `UNMATCHED` amounts grouped first by classification and then currency. These are gross review amounts, not accounting liability.
- `withdrawalAttentionRequired` includes `DISPATCH_UNKNOWN`, `REQUIRES_ACTION`, `FAILED`, and `REVERSED`.
- `settlementAttentionRequired` includes `DISPATCH_UNKNOWN`, `REQUIRES_ACTION`, `FAILED`, and `REVERSED`.
- `shippingAttentionRequired` includes `FAILED`, `UNKNOWN`, and `CANCELLED`.
- `anomalousPaymentReceipts` includes `UNDERPAID`, `OVERPAID`, `DUPLICATE`, `UNMATCHED`, and `FAILED` classifications.
- Fee totals are operational values and must not be labelled recognized accounting revenue.
- Dashboard metrics do not include V14 waybill rows, values, receipts, refunds or shipments. Use `/api/admin/waybills` and its detail route for waybill operations.

### Admin Lists And Filters

Every admin list is zero-based, defaults to `page=0&size=20`, rejects sizes outside `1..100`, and returns `PageResponse<T>`. Rows sort by immutable database ID descending. Reset `page` to zero when any filter changes, cancel stale requests, and request the next page only while `last === false`.

Filters on the same request are combined with AND. Omit unused query parameters instead of sending empty strings. Enum values are uppercase and case-sensitive; an invalid enum returns `400` with `Invalid request parameter: <name>`.

| Route | Optional filters |
|---|---|
| `/api/admin/users` | `role`, `verified`, `email` |
| `/api/admin/orders` | `creatorId`, `status`, `paymentStatus`, `settlementStatus`, `fundingMode` |
| `/api/admin/waybills` | `creatorId`, `status`, `paymentStatus` |
| `/api/admin/wallet-transactions` | `userId`, `orderId`, `transactionType` |
| `/api/admin/withdrawals` | `userId`, `status` |
| `/api/admin/settlements` | `orderId`, `status`, `settlementType` |
| `/api/admin/shipments` | `orderId`, `status`, `provider` |
| `/api/admin/payment-attempts` | `orderId`, `status` |
| `/api/admin/payment-receipts` | `orderId`, `classification`, `providerStatus` |

Filter details:

- User `email` is an exact normalized match, not a substring search. Trim and lowercase it before adding it to the URL. Redact that query parameter from frontend analytics and request logging.
- Shipment `provider` is an exact case-insensitive match after trimming.
- `verified=true` means `emailVerifiedAt` exists; `verified=false` means it does not.
- IDs must be positive JSON/URL integers. Detail routes return `404` for a missing user, order, or waybill. Admin waybill detail uses its numeric database `id`, not its public UUID.
- Admin reads have short server-side query timeouts to protect live traffic. Do not automatically hammer-retry a `500`; show a retry action with backoff and preserve the selected filters.

Examples:

```http
GET /api/admin/users?role=ADMIN&verified=true&page=0&size=20
GET /api/admin/orders?status=MANUAL_REVIEW&page=0&size=20
GET /api/admin/waybills?status=PAYMENT_REVIEW&paymentStatus=REVIEW&page=0&size=20
GET /api/admin/withdrawals?status=REQUIRES_ACTION&page=0&size=20
GET /api/admin/payment-receipts?providerStatus=SUCCESS&classification=UNMATCHED&page=0&size=20
```

### User Detail

`GET /api/admin/users/{userId}`

- `virtualAccount` is null when no dedicated virtual account exists.
- `withdrawalBankAccount` is null when the member has not saved one.
- Legacy users can have null profile, wallet, or timestamp fields; render fallbacks rather than asserting they exist.
- Account numbers, email, and phone values are already masked. Do not offer edit or reveal controls.
- `orderCount` counts orders created by the user. `withdrawalCount` counts that user's withdrawal records.

### Order Detail

`GET /api/admin/orders/{orderId}`

- `itemDescription` is capped at 120 characters in both admin order-list rows and the nested `order` summary on admin order detail.
- `counterparty`, `parcel`, `shipment`, `selectedShippingQuote`, and `settlement` can each be null depending on workflow progress.
- Creator bank fields are null. Counterparty bank fields exist only when that order flow requires an external payout account.
- Party responses include city, state, and country but omit street address and postal code.
- Shipment tracking URL is intentionally omitted. Only stored provider and tracking references are returned.
- Share tokens, guest access tokens, checkout authorization URLs, payment access codes, idempotency keys, and payout recipient codes are intentionally omitted.
- `paymentAttempts`, `paymentReceipts`, and `history` contain at most the latest 100 entries each and are newest first.
- A payment receipt can have `orderId`, `amount`, `currency`, `channel`, `occurredAt`, or receiver account set to null when the provider event could not be fully matched or parsed.

### Data Presentation

- Format amounts with their adjacent currency whenever the response provides one. Do not prepend a hardcoded naira symbol to records whose currency is not `NGN` or is null.
- `AdminWalletTransactionResponse` currently has no currency field. In a user-scoped ledger, use `AdminUserDetailResponse.user.walletCurrency` when available. In a global ledger, do not assume or aggregate a currency that the row does not identify; present the amount as currency-unspecified or omit the currency label.
- Use a decimal-safe money formatter/calculation library; never aggregate returned money with raw JavaScript floating point.
- Display timestamps according to their serialization type. Admin user/order `createdAt` can be `LocalDateTime` without an offset, operational order events are normally ISO-8601 `Instant` values ending in `Z`, and every V14 waybill/admin-waybill timestamp is UTC `LocalDateTime` without a suffix.
- Display failure reasons as untrusted text, never HTML. Do not send them to analytics or third-party support widgets automatically.
- Provider references and tracking references are operational identifiers. They may be copyable for authorized staff but must be redacted from analytics, session replay, and client logs.
- Masked values are display-only and must never be submitted back as bank, email, or phone input.

### Read-Only Scope

The backend currently has no admin mutation contract. The frontend must not simulate state changes optimistically or call member/guest mutation routes on behalf of a customer. Specifically, there is no supported admin endpoint for:

- Wallet balance adjustments.
- Withdrawal or settlement retries.
- Refunds or payment classification changes.
- Shipment retries or status overrides.
- User role changes, suspension, or deletion.
- Order cancellation, completion, or manual-review resolution.
- Waybill cancellation, refund, booking retry, payment reclassification, completion, or manual-review resolution.

Render these records as monitoring and support context only. Any future mutation requires a separately documented backend contract with durable audit records and concurrency rules.

## Authentication And Profile

### Register

`POST /api/auth/register`

```json
{
  "firstname": "Ada",
  "lastname": "Okafor",
  "email": "ada@example.com",
  "phoneNumber": "+2348012345678",
  "password": "Secure1!"
}
```

Registration creates an unverified member and sends a six-digit email code. It returns no JWT, wallet, or virtual account yet.

Example `202` response:

```json
{
  "challengeId": "email-challenge-id",
  "emailMasked": "a***@example.com",
  "expiresInSeconds": 299,
  "message": "Verification code sent"
}
```

Store the pending challenge in `sessionStorage`, never the code or password. Navigate to `/verify-email`. Do not ask for buyer/vendor role during registration.

### Verify Email

`POST /api/auth/verify-email`

```json
{
  "email": "ada@example.com",
  "challengeId": "email-challenge-id",
  "code": "123456"
}
```

Success returns `AuthResponse`. Only then save the JWT and member profile. The backend creates the zero-balance NGN wallet and Paystack DVA during verification.

`POST /api/auth/resend-verification`

```json
{
  "email": "ada@example.com"
}
```

Replace the stored challenge only after a successful response. Codes expire after five minutes and resend has a 60-second cooldown.

### Login

`POST /api/auth/login`

```json
{
  "email": "ada@example.com",
  "password": "Secure1!"
}
```

Success message:

```text
Login Successful
```

The member JWT defaults to one hour. There is no refresh-token or logout endpoint. Restore a session with `GET /api/users/me`.

An unverified account receives `403` with `Verify your email before signing in`. Offer the `/verify-email` flow instead of saving a session.

### Forgot And Reset Password

`POST /api/auth/forgot-password`

```json
{
  "email": "ada@example.com"
}
```

Always show the generic success message returned by the backend. The response intentionally does not reveal whether the account exists. If it exists, a six-digit email code is valid for five minutes.

`POST /api/auth/reset-password`

```json
{
  "email": "ada@example.com",
  "code": "123456",
  "newPassword": "NewSecure2!"
}
```

After success, clear any local member session and return to login. All JWTs issued before the password reset are invalid immediately.

### Current Member

`GET /api/users/me`

Use this after application reload. Legacy users may have null wallet or virtual-account fields.

`PATCH /api/users/me`

```json
{
  "firstname": "Ada",
  "lastname": "Okafor",
  "phoneNumber": "+2348012345678"
}
```

All three fields are required. The call provisions a missing virtual account for a legacy user. Email is immutable.

## Banks And Payout Accounts

### List Banks

`GET /api/banks`

Public bare-array response:

```json
[
  {
    "name": "Guaranty Trust Bank",
    "code": "058"
  }
]
```

### Resolve For A Member

`POST /api/bank-accounts/resolve`

This route requires a member JWT.

### Historical Guest Vendor Resolution (Removed In V16)

`POST /orders/token/{shareToken}/bank-account/resolve`

This route is public only while the order is awaiting a guest vendor. Use this route on a buyer-created order share page.

Request for either resolve route:

```json
{
  "accountNumber": "0123456789",
  "bankCode": "058"
}
```

Response:

```json
{
  "accountNumberMasked": "******6789",
  "accountName": "BOLA VENDOR",
  "bankCode": "058",
  "bankName": "Guaranty Trust Bank"
}
```

Keep the raw account number only in component memory and clear it after counterparty submission.

## Legacy V15 Order Walkthrough

> The request bodies and link timing in this walkthrough are superseded by the V16 changed DTOs and contract status sections. Retain only unchanged read, payment, tracking, and settlement guidance.

### Create Buyer-Created Order

`POST /orders`

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
  }
}
```

Do not send `parcel`. The guest vendor supplies pickup and parcel information.

### Create Vendor-Created Order

```json
{
  "amount": 125000.00,
  "itemDescription": "MacBook Air M3",
  "creatorRole": "VENDOR",
  "creatorAddress": {
    "line1": "12 Marina Road",
    "line2": null,
    "city": "Lagos",
    "state": "Lagos",
    "country": "NG",
    "postalCode": "100001"
  },
  "parcel": {
    "weightKg": 2.5,
    "lengthCm": 40,
    "widthCm": 30,
    "heightCm": 15
  }
}
```

The initial status is `AWAITING_COUNTERPARTY`.

The create response includes both `shareToken` and `shareableLink`. Authenticated creator responses from `GET /orders` and `GET /orders/{id}` also include `shareableLink` for every order status, allowing the creator to copy the link again without storing the create response. They do not expose a separate `shareToken` field.

### List Creator Orders

`GET /orders?page=0&size=20`

Returns `PageResponse<OrderDetailsResponse>`, newest first. Render `content` and request the next zero-based page while `last` is false. Each entry includes `shareableLink` for a copy-link action and is a full `OrderDetailsResponse` that can include counterparty PII. Do not persist this response in long-lived browser storage.

### Private Order Details

`GET /orders/{orderId}`

Only the creator can read it. Use its `shareableLink` field for the order-detail copy-link action.

Payment-instruction visibility:

- `fundingMode: "WALLET"`: creator details return `paymentInstructions: null`. Top-up instructions are returned only by an insufficient wallet-payment attempt.
- `fundingMode: "PAYSTACK_CHECKOUT"`: creator details return `paymentInstructions: null`; only the verified guest buyer can initialize checkout.
- `fundingMode: "DIRECT_DVA"`: legacy only. Existing private visibility rules remain for already-issued transfer instructions.
- Branch on `fundingMode`; do not infer funding behavior only from creator role or payment status.

### Order History

`GET /orders/{orderId}/history`

Returns oldest first. Use it for a timeline, but use the order response as the current source of truth.

## Historical V15 Public Guest Onboarding (Removed In V16)

### Safe Public Order

`GET /orders/token/{shareToken}`

This response never includes creator contact details, address, share token, virtual account, pricing breakdown, guest information, bank information, parcel, or tracking.

Render guest fields from `counterpartyRole`, not from assumptions about the creator.

### Guest Vendor Submission

Used when public `counterpartyRole === "VENDOR"`.

```json
{
  "firstName": "Bola",
  "lastName": "Adeyemi",
  "email": "bola@example.com",
  "phoneNumber": "+2348012345678",
  "address": {
    "line1": "12 Marina Road",
    "line2": null,
    "city": "Lagos",
    "state": "Lagos",
    "country": "NG",
    "postalCode": "100001"
  },
  "externalBankAccount": {
    "accountNumber": "0123456789",
    "bankCode": "058"
  },
  "parcel": {
    "weightKg": 2.5,
    "lengthCm": 40,
    "widthCm": 30,
    "heightCm": 15
  }
}
```

### Guest Buyer Submission

Used when public `counterpartyRole === "BUYER"`.

```json
{
  "firstName": "Tobi",
  "lastName": "Adebayo",
  "email": "tobi@example.com",
  "phoneNumber": "+2348022222222",
  "address": {
    "line1": "2 Buyer Road",
    "line2": null,
    "city": "Abuja",
    "state": "FCT",
    "country": "NG",
    "postalCode": "900001"
  }
}
```

Do not send bank or parcel fields for a guest buyer.

Submit either body to:

`POST /orders/token/{shareToken}/counterparty-info`

Success is `202 Accepted` with `OtpChallengeResponse`.

### Verify OTP

`POST /orders/token/{shareToken}/verify-otp`

```json
{
  "challengeId": "challenge-id",
  "code": "123456"
}
```

Successful verification moves the order to `AWAITING_SHIPPING_RATE`, not `AWAITING_PAYMENT`, and returns an order-scoped guest token.

### Resend OTP

`POST /orders/token/{shareToken}/resend-otp`

```json
{
  "challengeId": "current-challenge-id"
}
```

A successful resend returns a new challenge ID and invalidates the old one.

### Access Recovery

Request a recovery OTP:

`POST /orders/token/{shareToken}/access-recovery-otp`

No request body.

Recover and rotate the token:

`POST /orders/token/{shareToken}/recover-access`

```json
{
  "challengeId": "recovery-challenge-id",
  "code": "123456"
}
```

Recovery is unavailable after `COMPLETED` or `CANCELLED`.

## OTP And Guest Token Rules

OTP behavior:

- Six digits.
- Five-minute expiry.
- Sixty-second issue/resend cooldown.
- Maximum five failed verification attempts.
- Same code is delivered by SMS and email.
- New challenge invalidates the previous active challenge.
- Resubmitting counterparty details invalidates the old challenge.
- OTP cooldown is an application `400`, not `429`.

Store the current challenge ID and expiry in `sessionStorage`. Always replace it after resend.

Guest token behavior:

- Default lifetime is seven days.
- Token is valid only for one order.
- New token issuance revokes previous active tokens.
- Missing guest header returns `400`.
- Invalid, expired, revoked, or wrong-order token returns `403`.

Recommended key:

```text
sessionStorage["shopsecure:guest:<shareToken>"]
```

Never put JWTs, guest tokens, OTPs, or challenge IDs in URLs, analytics, persisted Redux state, or error reports.

## Shipping Quotes And Selection

The buyer always selects shipping.

Buyer-created order:

- Registered buyer calls member quote routes.

Vendor-created order:

- Verified guest buyer calls guest quote routes.

### Request Quotes As Order Creator

`POST /orders/{orderId}/shipping/quotes`

No request body.

### Historical Guest Quote Request (Removed In V16)

`POST /orders/token/{shareToken}/shipping/quotes`

Requires `X-Order-Access-Token`. No request body.

Quote response example:

```json
[
  {
    "quoteId": 61,
    "carrierName": "DHL Express",
    "carrierLogo": "https://example.com/dhl.png",
    "serviceDescription": "Express delivery",
    "deliveryFee": 15000.00,
    "serviceFee": 500.00,
    "totalAmount": 140500.00,
    "currency": "NGN",
    "pickupEstimate": "Within 24 hours",
    "deliveryEstimate": "Within 3 days"
  }
]
```

The response does not include quote expiry. If selection reports an expired quote, request fresh quotes and replace the list.

### Select Quote

Member route:

`PUT /orders/{orderId}/shipping/selection`

Guest route:

`PUT /orders/token/{shareToken}/shipping/selection`

```json
{
  "quoteId": 61
}
```

Selection freezes pricing, sets payment status to `PENDING`, and changes the order to `AWAITING_PAYMENT`.

- Buyer-created order: response has `fundingMode: "WALLET"` and `paymentInstructions: null`.
- New vendor-created order: response has `fundingMode: "PAYSTACK_CHECKOUT"` and `paymentInstructions: null`.

Do not blindly retry selection after a timeout. Refresh order/status first. A successful first call means the retry returns an invalid-state `400`.

Multiple checkout or wallet orders for the same creator may be payable simultaneously. Always scope frontend payment state by order ID/share token.

## Pricing And Payment

Formula:

```text
deliveryFee = provider shipping rate + ShopSecure markup
buyer total = itemAmount + deliveryFee + serviceFee
seller settlement = itemAmount
```

Never hardcode markup or service-fee defaults. Never submit a client-calculated total. Display backend response fields.

DVA wallet top-up instructions example:

```json
{
  "virtualAccount": {
    "accountNumber": "9930000902",
    "bankName": "Paystack-Titan",
    "accountName": "SHOPSECURE / ADA OKAFOR",
    "expiresAt": null
  },
  "amount": 140500.00,
  "currency": "NGN",
  "paymentReference": "order_91_payment_abc123",
  "narration": "SHOPSECURE order_91_payment_abc123"
}
```

### Buyer-Created Wallet Payment

New buyer-created orders have `fundingMode: "WALLET"`. After quote selection, show a `Pay from wallet` action that calls:

`POST /orders/{orderId}/payments/wallet`

```http
Authorization: Bearer <jwt>
Idempotency-Key: <attempt-uuid>
```

There is no request body. Always use the backend response rather than comparing a previously fetched balance in the browser.

Sufficient balance response:

```json
{
  "orderId": 91,
  "outcome": "FUNDED",
  "status": "SHIPMENT_BOOKING",
  "paymentStatus": "CONFIRMED",
  "amountRequired": 140500.00,
  "walletBalance": 9500.00,
  "shortfall": 0.00,
  "currency": "NGN",
  "topUpInstructions": null,
  "fundedAt": "2026-08-08T16:00:00Z"
}
```

Insufficient balance response:

```json
{
  "orderId": 91,
  "outcome": "INSUFFICIENT_FUNDS",
  "status": "AWAITING_PAYMENT",
  "paymentStatus": "PENDING",
  "amountRequired": 140500.00,
  "walletBalance": 40000.00,
  "shortfall": 100500.00,
  "currency": "NGN",
  "topUpInstructions": {
    "virtualAccount": {
      "accountNumber": "9930000902",
      "bankName": "Paystack-Titan",
      "accountName": "SHOPSECURE / ADA OKAFOR",
      "expiresAt": null
    },
    "amount": 100500.00,
    "currency": "NGN",
    "paymentReference": "order_91_payment_abc123",
    "narration": "SHOPSECURE WALLET TOPUP order_91_payment_abc123"
  },
  "fundedAt": null
}
```

Wallet rules:

- The debit is all-or-nothing. `INSUFFICIENT_FUNDS` never changes the wallet balance or order payment state.
- Display the exact `shortfall` and top-up bank details with copy actions.
- A signed Paystack webhook credits a valid DVA transfer to the member wallet exactly once.
- A top-up does not pay the order. Refresh `GET /api/wallet`, then let the buyer explicitly call the wallet-payment endpoint again.
- Generate a new idempotency key for a new intentional attempt after an explicit insufficient response. Reuse the prior key only when retrying an ambiguous timeout.
- Replaying a successful key returns the funded result. A different key after funding returns `400` and cannot debit again.
- A wallet-funded order can be cancelled while unpaid and still in `AWAITING_PAYMENT`, provided no successful direct receipt exists.

### Vendor-Created Paystack Checkout

New vendor-created orders have `fundingMode: "PAYSTACK_CHECKOUT"`. After quote selection, initialize checkout with:

`POST /orders/token/{shareToken}/payments/checkout`

```http
X-Order-Access-Token: <guest-token>
```

There is no request body and no frontend-generated idempotency key. Immediate repeats return the same persisted session. Once the last provider check is at least 15 minutes old, the backend verifies it with Paystack. A successful exact verification confirms payment, a failed or abandoned transaction gets a new session, and pending/unknown results or temporary Paystack failures keep returning the persisted URL without repeatedly calling Paystack.

Example response:

```json
{
  "orderId": 92,
  "fundingMode": "PAYSTACK_CHECKOUT",
  "status": "AWAITING_PAYMENT",
  "paymentStatus": "PENDING",
  "amount": 140500.00,
  "currency": "NGN",
  "reference": "checkout-order-92-2f4a6b8c10d14e31",
  "authorizationUrl": "https://checkout.paystack.com/example",
  "accessCode": "example-access-code",
  "initializedAt": "2026-08-08T18:00:00Z"
}
```

Checkout rules:

- Navigate the browser to `authorizationUrl`. Do not initialize a Paystack transaction from frontend code and never send a Paystack secret to the browser.
- Treat `authorizationUrl` as an external URL. A full-page redirect is preferred; if opening a new tab, use `noopener noreferrer`.
- `accessCode` is returned for supported Paystack popup/mobile integrations, but hosted redirect checkout needs only `authorizationUrl`.
- Paystack returns the browser to the share page after checkout. A browser callback or redirect never proves payment.
- Poll guest status after return. A signed backend webhook normally changes payment to `CONFIRMED`; if that delivery is lost, a later checkout retry can confirm the same transaction through authenticated server-to-server Paystack verification. Browser callbacks never confirm payment.
- The backend matches the unique reference plus exact amount and currency. Equal-value payments for different orders remain distinct.
- A failed or abandoned attempt permits a later initialization. Pending or unknown verification continues returning the existing session; a successful exact verification confirms the order. A late success still maps to its original order.
- Once any checkout URL has been initialized, direct cancellation is blocked because a late payment remains possible.
- Do not call the member wallet-payment endpoint for checkout orders.
- Checkout payment funds the order but does not pay the vendor yet. For new orders, item amount only is transferred to the frozen vendor bank recipient after public tracking-link delivery confirmation.

`DIRECT_DVA` remains possible only on migrated orders that had transfer instructions before V9. Do not build it as the normal vendor-order flow.

Payment-instruction visibility:

- Wallet-mode buyer creator receives top-up instructions only from an insufficient wallet attempt.
- Checkout-mode guest buyer receives an `authorizationUrl` only from the checkout initialization mutation.
- Legacy direct-mode guest buyers may still receive the vendor creator's virtual account in status responses.
- Guest vendor never sees the buyer creator's virtual account.
- Public order response never includes a virtual account.

## Guest Status And Polling

`GET /orders/token/{shareToken}/status`

Requires `X-Order-Access-Token`.

Important response behavior:

- `amountExpected` is null before quote selection.
- `amountReceived` is null, not zero, before a counted successful receipt.
- `fundingMode` is the authoritative payment UI discriminator after reload.
- New checkout orders always return `paymentInstructions: null`; call the checkout initialization endpoint to retrieve or replay the active URL.
- Payment instructions can be present only for migrated `DIRECT_DVA` guest-buyer orders while status is exactly `AWAITING_PAYMENT`.
- Guest vendor status never contains the buyer creator's virtual account.
- `shipping` is the stored shipment state; there is no guest provider-refresh endpoint.

Recommended polling:

| State | Interval | Endpoint |
|---|---|---|
| Creator waiting for counterparty | 10 to 15 seconds | `GET /orders/{id}` |
| Registered buyer waiting for wallet top-up | 5 to 10 seconds | `GET /api/wallet`; payment remains a manual mutation |
| Guest buyer returned from checkout | 3 to 5 seconds initially, then 10 | Guest status |
| Shipment booking/recovery | 10 seconds, then 20 to 30 | Private details or guest status |
| Ready/tracking | 15 to 30 seconds | Private details or guest status |
| Payout pending/retrying | 10 seconds | Private details or guest status |

Pause polling while the tab is hidden or offline. Revalidate when focus returns. There is no SSE or WebSocket endpoint.

## Tracking And Cancellation

### Creator Tracking Refresh

`GET /orders/{orderId}/shipping/tracking`

This calls the provider and returns refreshed shipping data. Only the creator can call it. Guests see stored shipping state through guest status.

Open `trackingUrl` with:

```html
rel="noopener noreferrer"
```

Treat `carrierLogo` and `trackingUrl` as untrusted external URLs.

### Cancel Order

`POST /orders/{orderId}/shipping/cancel`

Despite the route name, label this action `Cancel order`.

Direct cancellation is allowed only in:

- `AWAITING_COUNTERPARTY`
- `AWAITING_SHIPPING_RATE`
- `AWAITING_PAYMENT` when `fundingMode === "WALLET"`, payment is unconfirmed, and no successful direct receipt exists
- `AWAITING_PAYMENT` when `fundingMode === "PAYSTACK_CHECKOUT"` and checkout has not been initialized

The response can be `200` with no body if no shipment draft exists. Refresh the order after success or an ambiguous timeout.

For `PAYSTACK_CHECKOUT`, hide cancellation as soon as checkout initialization succeeds. For `WALLET`, keep it available while the order remains eligible and hide it after wallet funding, any successful direct receipt, or payment confirmation. Migrated `DIRECT_DVA` orders remain non-cancellable after quote selection.

## Delivery Confirmation And Settlement

New orders return `escrowReleasePolicy: "BUYER_DELIVERY_CONFIRMATION"`. Render the confirmation action on the public ShopSecure tracking page; do not request a delivery OTP for this action.

The backend distinguishes carrier evidence from escrow release:

- Shipment `PENDING` or `CONFIRMED`: booking/assignment only; escrow remains held.
- Shipment `IN_TRANSIT`: authenticated pickup evidence is recorded; escrow remains held.
- Shipment `DELIVERED`: carrier delivery is visible, but escrow still waits for tracking-link confirmation.

The first pickup observation records `carrierPickupConfirmedAt` and creates durable pickup emails for buyer and vendor. It does not create a payout settlement for a current order. Poll the tracking snapshot and enable the confirmation control while the receiver has the item, `escrow.releasePolicy` is `BUYER_DELIVERY_CONFIRMATION`, `escrow.status` is `NOT_STARTED`, and `deliveryConfirmedAt` is null:

```ts
const pickupConfirmed = order.carrierPickupConfirmedAt != null;
const payoutProcessing = [
  "PENDING",
  "DISPATCH_UNKNOWN",
  "REQUIRES_ACTION",
  "FAILED",
].includes(order.settlementStatus);
```

State behavior:

- Pickup accepted: order remains `READY_FOR_DELIVERY`; settlement remains `NOT_STARTED`.
- Public confirmation accepted: order `PAYOUT_PENDING`; settlement normally `PENDING`.
- Payout success: order and settlement become `COMPLETED`; shipment tracking can still remain `IN_TRANSIT` until its own carrier update.
- Failed/ambiguous payout: show processing or support state; retry only with the same idempotency key and never submit a second payout intent.

After Paystack confirms success, the backend sends buyer and vendor settlement email/SMS with buyer-confirmation wording. Keep shipment tracking visible if the carrier snapshot has not reached `DELIVERED`.

Already-created orders can return `escrowReleasePolicy: "CARRIER_PICKUP"`. Those rows retain automatic settlement on authenticated carrier `IN_TRANSIT`/`DELIVERED` evidence and reject manual confirmation. Never infer policy from dates or missing local state.

## Wallet

### Current Wallet

`GET /api/wallet`

There is one balance only. Do not build available/processing balance UI.

### Wallet Ledger

`GET /api/wallet/transactions?page=0&size=20`

Returns `PageResponse<WalletTransactionResponse>`, newest first. `page` is zero-based; `size` defaults to 20 and cannot exceed 100.

- `DVA_CREDIT`: incoming dedicated-account top-up; `orderId` is null.
- `ORDER_PAYMENT_DEBIT`: buyer-created order payment; `orderId` identifies the order.
- `ORDER_RELEASE`: vendor-created order settlement credit.
- `LEGACY_ESCROW_DEBIT`: migrated history only.

### Withdrawal Bank Account

Users may add or replace a withdrawal account at any time. Submit the same verified Nigerian bank fields used by the existing account-resolution flow:

`PUT /api/wallet/bank-account`

```json
{
  "accountNumber": "0123456789",
  "bankCode": "058"
}
```

The backend resolves the account with Paystack, creates a transfer recipient, encrypts the account number, and returns only masked details. Read the saved account with `GET /api/wallet/bank-account`; a missing account returns `404`.

### Wallet Withdrawal

Only `GET /api/wallet` balance is withdrawable. New-order vendor proceeds go directly to the frozen external bank recipient after carrier pickup and do not enter the ShopSecure wallet. `ORDER_RELEASE` wallet credits apply only to legacy settlement records.

1. Request a code for the exact amount:

`POST /api/wallet/withdrawals/challenge`

```json
{
  "amount": 25000.00
}
```

2. Submit the withdrawal:

`POST /api/wallet/withdrawals`

```http
Idempotency-Key: <stable-attempt-uuid>
```

```json
{
  "amount": 25000.00,
  "challengeId": "withdrawal-challenge-id",
  "code": "123456"
}
```

The code is sent by SMS and email and is bound to the exact amount and current bank account. Changing either requires a new code. The backend atomically debits/reserves the available balance before calling Paystack. A success completes only when provider verification matches the frozen reference, transfer ID, amount, currency, and recipient; an initiation response that exposes only Paystack's numeric recipient ID remains pending until exact verification. Reuse the same idempotency key after an ambiguous client timeout; never create a new key for that same intentional withdrawal.

Withdrawal statuses:

- `PENDING` / `DISPATCH_UNKNOWN`: funds remain reserved; show processing and do not tell the user to retry.
- `COMPLETED`: Paystack confirmed the transfer.
- `FAILED` / `REVERSED`: the backend credits the reserved amount back exactly once.
- `REQUIRES_ACTION`: provider/operations review; funds remain reserved.

Read paginated history with `GET /api/wallet/withdrawals?page=0&size=20`. Refresh `GET /api/wallet` after any withdrawal status change.

## Frontend State Matrix

### Post-Payment Notifications

No frontend mutation is required for payment notifications. After the backend commits the first successful funding transition, it sends email and SMS to both participants:

- Vendor: buyer payment is confirmed; prepare the item; the delivery partner will contact the vendor with pickup details.
- Buyer: the order was created and payment confirmed; the vendor has been notified; pickup is being arranged.

Wallet-payment retries with the same idempotency key and duplicate checkout callbacks do not resend these messages. Notification failures do not change order payment status, so the frontend must continue using order status rather than assuming message delivery.

### Order State

| Status | Frontend behavior |
|---|---|
| `AWAITING_COUNTERPARTY` | Show share/cancel to creator; guest can submit details and OTP. |
| `AWAITING_SHIPPING_RATE` | The order creator requests and selects quotes; creator can still cancel. |
| `AWAITING_PAYMENT` | Branch on `fundingMode`: show wallet payment/top-up UI to the registered buyer, or checkout initialization/return polling to the guest buyer. |
| `SHIPMENT_BOOKING` | Poll only; backend is booking pickup. |
| `READY_FOR_DELIVERY` | Show tracking. For `BUYER_DELIVERY_CONFIRMATION`, allow the public tracking-link holder to confirm after receipt. |
| `PAYOUT_PENDING` | Poll only; payout verification is automatic. |
| `IN_TRANSIT` | Persisted `CARRIER_PICKUP` order whose vendor payout succeeded while carrier delivery continues; keep tracking prominent. |
| `COMPLETED` | Settlement completed after confirmation, or legacy pickup settlement plus carrier delivery; continue to show carrier tracking when needed. |
| `PAYMENT_FAILED` | Read-only support state; no dedicated frontend recovery endpoint. |
| `SHIPPING_FAILED` | Show automatic recovery/support state; do not create a manual retry button. |
| `PAYOUT_FAILED` | Show support state; do not initiate or promise an automatic second-reference payout. |
| `CANCELLED` | Terminal cancelled state. |
| `MANUAL_REVIEW` | Terminal from frontend perspective; show support escalation. |

Shipping booking retries use persisted backoff and run automatically. Defaults are 5 attempts with increasing delays before `MANUAL_REVIEW`.

### Payment State

| Status | Frontend behavior |
|---|---|
| `NOT_STARTED` | No payment screen; quote not selected. |
| `PENDING` | For `WALLET`, show explicit wallet payment/retry; for `PAYSTACK_CHECKOUT`, initialize/replay checkout or poll after return. |
| `UNDERPAID` | Manual-review state for checkout; migrated direct orders may show an outstanding amount when `amountReceived` is available. |
| `OVERPAID` | Disable payment prompts and show manual-review messaging. |
| `CONFIRMED` | Hide payment instructions and wait for shipping. |
| `FAILED` | No funds were confirmed; if order remains payable, allow transfer retry. |

### Settlement State

| Status | Frontend behavior |
|---|---|
| `NOT_STARTED` | Escrow is still held; current orders wait for receiver delivery confirmation. |
| `PENDING` | Payout queued or dispatched; poll. |
| `DISPATCH_UNKNOWN` | Backend is verifying an ambiguous result; no user action. |
| `COMPLETED` | Settlement succeeded. |
| `FAILED` | Legacy failure value; show support. New definitive failures stop in `REQUIRES_ACTION`. |
| `REVERSED` | Terminal support state for reversed external payout. |
| `REQUIRES_ACTION` | Operations intervention required after a definitive rejection/failure or mismatched provider reference; do not initiate payout. |

### Shipment State

| Status | Frontend behavior |
|---|---|
| `DRAFT` | Quote/payment stage; no settlement. |
| `PENDING` | Provider accepted booking; show tracking where available. |
| `CONFIRMED` | Pickup booked. |
| `IN_TRANSIT` | Show in-transit tracking. |
| `DELIVERED` | Carrier reports fulfillment complete. It catches up automatic settlement only for persisted `CARRIER_PICKUP` orders. |
| `CANCELLED` | Show support/cancelled state. A completed external payout requires manual financial review. |
| `FAILED` | Show recovery/support state. A completed external payout requires manual financial review. |
| `UNKNOWN` | Show waiting/support state. |

## Buyer-Created Flow

Registered creator is buyer; the recorded counterparty is vendor.

1. Member creates the complete order with both parties, both addresses, parcel, and vendor bank account.
2. Registered buyer requests and selects a shipping quote.
3. Selection freezes the total and returns `fundingMode: "WALLET"`.
4. Buyer explicitly calls wallet payment with an idempotency key; an insufficient response contains DVA top-up instructions.
5. After funding, the backend books shipping and emails both parties the stable tracking link.
6. Carrier `CONFIRMED` means awaiting pickup. Carrier `IN_TRANSIT` records pickup but leaves escrow held.
7. After receiving the item, the buyer confirms through the public tracking page with a stable idempotency key; no login or OTP is sent.
8. Frontend polls settlement while continuing to display shipment tracking; payout success completes the order settlement.

The vendor never receives delivery/service charges and never receives a ShopSecure wallet credit for a new order.

## Vendor-Created Flow

Registered creator is vendor; the recorded counterparty is buyer.

1. Member creates the complete order with both parties, both addresses, parcel, and vendor bank account.
2. Vendor requests and selects a quote; selection creates the buyer payment-only link.
3. Buyer verifies payment access, initializes Paystack Checkout, and completes the frozen total.
4. The signed Paystack callback funds the order and starts carrier booking.
5. Both parties use the stable tracking link. No buyer delivery OTP is requested for a new order.
6. Carrier `IN_TRANSIT` records pickup but leaves escrow held.
7. After receipt, the buyer uses the tracking-page confirmation action; payout success completes the order settlement.

The vendor creator owns shipping selection. The public tracking URL is a bearer capability, so anyone holding that shared URL can submit the confirmation action.

## Historical V14 Waybill Walkthrough (Superseded In V16)

> Do not implement the party-completion or invitee quote-selection routes in this section. The current V16 flow and exact DTO overrides are at the top of this handoff.

### Separate Domain

Waybills are delivery-only records under `/waybills/**`. They do not reuse escrow `/orders/**` records or APIs. Never show or call wallet payment, DVA transfer, bank-account resolution, escrow, item pricing, settlement, payout, withdrawal or buyer delivery-confirmation features for a waybill. The only payment method is Paystack Checkout for the backend-calculated delivery total, and Terminal Africa `DELIVERED` completes the waybill automatically.

Waybill roles and payment responsibility are independent:

- `SENDER` is always the pickup party.
- `RECIPIENT` is always the delivery party.
- `creatorRole` says which party the authenticated creator represents. An invited-payer creator must be `SENDER`.
- `payerType` determines both the required create shape and who controls quotes and checkout.

| `payerType` | Required create body | Initial state | Quote and payment owner |
|---|---|---|---|
| `CREATOR` | Complete `oppositeParty` required | `AWAITING_QUOTE` | Authenticated creator; no invitation is created |
| `INVITED_PAYER` | `creatorRole: "SENDER"`; omit `oppositeParty` | `AWAITING_PARTY_DETAILS` | `PARTY_COMPLETION` targets `RECIPIENT`, `canPay=true`; verified recipient controls quotes and checkout |

The creator's party is an `ACCOUNT_SNAPSHOT`. A creator-paid opposite party is `CREATOR_PROVIDED` and complete without public OTP verification. The recipient on an invited-payer waybill becomes `INVITEE_PROVIDED` only after OTP verification. Any other payer/party combination returns `400` without creating a waybill or invitation.

### Creator Routes

Every creator route requires `Authorization: Bearer <jwt>` and enforces creator ownership.

| Method | Route | Additional header/body | Success |
|---|---|---|---|
| `POST` | `/waybills` | `Idempotency-Key`; JSON `CreateWaybillRequest` | `201 CreateWaybillResponse` |
| `GET` | `/waybills?page=0&size=20` | No body | `200 PageResponse<WaybillDetailsResponse>` |
| `GET` | `/waybills/{publicId}` | No body | `200 WaybillDetailsResponse` |
| `GET` | `/waybills/{publicId}/history` | No body | `200 WaybillStatusHistoryResponse[]` |
| `POST` | `/waybills/{publicId}/invitations/reissue` | No body | `200 WaybillInvitationReissueResponse` |
| `POST` | `/waybills/{publicId}/shipping/quotes` | No body; creator payer only | `200 WaybillQuoteResponse[]` |
| `PUT` | `/waybills/{publicId}/shipping/selection` | Creator payer only; JSON `{"quoteId": 44}` | `200 WaybillQuoteResponse` |
| `POST` | `/waybills/{publicId}/payments/checkout` | `Idempotency-Key`; no body; creator payer only | `200 WaybillCheckoutResponse` |
| `GET` | `/waybills/{publicId}/shipping/tracking` | No body; provider refresh | `200 WaybillShipmentResponse` |
| `POST` | `/waybills/{publicId}/cancel` | `Idempotency-Key`; JSON `{}` or `{"reason":"..."}` | `200 WaybillCancellationResponse` |

The list is zero-based, defaults to `size=20`, permits `1..100`, sorts by `createdAt` then `id` descending and returns the standard page envelope. History is a bare array ordered oldest first. `publicId` is the UUID-like external ID; never substitute the numeric admin ID.

Expected errors are `400` for validation, missing headers, idempotency conflicts, provider/business errors or invalid state; `403` for another owner; and `404` for a missing waybill, quote or shipment. Member authentication failures can be `401` or `403`. There is no `409` waybill contract.

Creation example that leaves recipient completion and payment to one invitee:

```http
POST /waybills
Authorization: Bearer <jwt>
Idempotency-Key: <stable-create-uuid>
Content-Type: application/json
```

```json
{
  "creatorRole": "SENDER",
  "payerType": "INVITED_PAYER",
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
  }
}
```

Required validation:

- `description`: nonblank, at most 2,000 characters.
- `declaredValue`: positive, at most two decimal places; this is carrier valuation, not an amount added to payment.
- `CREATOR`: send a complete `oppositeParty`; either creator role is valid.
- `INVITED_PAYER`: send `creatorRole: "SENDER"` and omit `oppositeParty`.
- Both party addresses: `country: "NG"`, postal code required, and the shared address limits.
- Names: nonblank, at most 100; email valid and at most 255; phone is a valid Nigerian number and is normalized to `+234...`.
- Parcel weight: positive, at most three decimals; optional positive dimensions have at most two decimals.
- Do not submit `quantity` or `parcelType`; responses return persisted `1` and `"parcel"`.

Creation is idempotent per creator after normalization. Reuse the same key and identical body after an ambiguous timeout. A replay still returns HTTP `201`, has `replayed: true`, and deliberately returns `invitationLink: null` and `expiresAt: null`. Reusing the key for a different normalized body returns `400`. Use reissue if the original link was lost.

### Invitation Routes

The raw `{invitationToken}` in the frontend URL is a bearer credential. Do not send the member JWT on these routes.

| Method | Route | Header/body | Success |
|---|---|---|---|
| `GET` | `/waybills/invitations/{invitationToken}` | None | `200 PublicWaybillInvitationResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/party-details` | JSON `WaybillPartyRequest`; `PARTY_COMPLETION` only | `202 WaybillOtpChallengeResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/payer-claim` | Legacy JSON `WaybillPayerClaimRequest`; `PAYMENT_ONLY` only | `202 WaybillOtpChallengeResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/verify-otp` | JSON `VerifyWaybillOtpRequest` | `200 WaybillAccessResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/resend-otp` | JSON `ResendWaybillOtpRequest` | `200 WaybillOtpChallengeResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/access-recovery` | No body; invitation must already be verified | `202 WaybillOtpChallengeResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/access-recovery/verify` | JSON `VerifyWaybillOtpRequest` | `200 WaybillAccessResponse` |
| `GET` | `/waybills/invitations/{invitationToken}/status` | `X-Waybill-Access-Token` | `200 WaybillAccessResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/shipping/quotes` | `X-Waybill-Access-Token`; no body | `200 WaybillQuoteResponse[]` |
| `PUT` | `/waybills/invitations/{invitationToken}/shipping/selection` | `X-Waybill-Access-Token`; JSON `{"quoteId": 44}` | `200 WaybillQuoteResponse` |
| `POST` | `/waybills/invitations/{invitationToken}/payments/checkout` | `X-Waybill-Access-Token`, `Idempotency-Key`; no body | `200 WaybillCheckoutResponse` |

Branch the public page only from `invitationKind`, `detailsRequired`, `canPay` and `targetPartyRole`. Representative exact public summary after a party-completion form has been staged:

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

Party completion sends the full delivery-party body shown by `WaybillPartyRequest`. For previously persisted `PAYMENT_ONLY` links only, payer claim omits an address; current creation never produces this branch:

```json
{
  "firstName": "Kemi",
  "lastName": "Payer",
  "email": "kemi@example.com",
  "phoneNumber": "+2348033333333"
}
```

OTP verification and recovery verification both send:

```json
{
  "challengeId": "otp-challenge-id",
  "code": "123456"
}
```

Resend sends only the current challenge ID:

```json
{
  "challengeId": "otp-challenge-id"
}
```

OTP issue returns exactly:

```json
{
  "challengeId": "replacement-challenge-id",
  "partyRole": "RECIPIENT",
  "emailMasked": "t***@***",
  "phoneNumberMasked": "************22",
  "expiresInSeconds": 299
}
```

`partyRole` is `null` for `PAYMENT_ONLY`. The code is six digits, expires in five minutes, is sent to the same staged email and normalized Nigerian phone, has a 60-second issue/resend cooldown and is single-use. Each challenge permits five wrong-code attempts. One invitation defaults to five total sends across initial verification, resend and recovery, and locks after ten invitation-wide failures. There is no public unlock route.

Initial verification returns a one-time plaintext `accessToken`. Save it only in session-scoped memory/storage keyed by `waybillPublicId`; `GET .../status` returns the same DTO with `accessToken: null`. Access recovery requires the still-active raw invitation, sends a fresh OTP to the staged contact and rotates the access-token hash. The old token immediately receives `403`. Reissue and cancellation also revoke access.

Unknown, expired and revoked raw tokens return `404` on public summary/onboarding/OTP/recovery routes. Invalid or mismatched bearer credentials on `/status`, `/shipping/quotes`, `/shipping/selection`, and `/payments/checkout` return `403`. Missing required access or idempotency headers return `400`.

### Link Reissue And No-Store Rules

`FRONTEND_BASE_URL`, not `APP_BASE_URL`, creates links in this exact form after trailing slashes are removed:

```text
${FRONTEND_BASE_URL}/waybills/invitations/{rawToken}
```

The default invitation lifetime is seven days (`PT168H`), and the database stores SHA-256 hashes rather than plaintext invitation/access tokens. Creator list/detail returns only active invitation metadata, never a recoverable raw token. Reissue revokes all active invitations/access tokens and returns one fresh recipient `PARTY_COMPLETION` link. It is allowed only while an invited-payer waybill is `AWAITING_PARTY_DETAILS`; after recipient verification, use access recovery.

Every response, including errors, under `/waybills/invitations/**` includes:

```http
Cache-Control: no-store
Pragma: no-cache
Referrer-Policy: no-referrer
```

The same headers are returned by creator creation, invitation reissue and creator checkout because those responses can carry a link or checkout secret. Fetch these routes with `cache: "no-store"`. Configure the invitation page itself with `Referrer-Policy: no-referrer`; redact the complete URL/token, access token, OTP challenge, checkout URL/access code and payment reference from analytics, logs, session replay and error reporting.

Verified `PARTY_COMPLETION` status exposes masked names, email, phone and city/state/country but never street lines or postal code. Verified `PAYMENT_ONLY` status always has `parties: []`. Public email masking is first character plus `***@***`; public phone masking exposes only the final two characters.

### Pricing, Quotes And Expiry

Waybills are NGN-only and Nigeria-only. The backend formula is:

```text
deliveryFee = Terminal provider rate + SHIPPING_DELIVERY_MARKUP
totalAmount = deliveryFee + SHIPPING_SERVICE_FEE
```

`declaredValue` is excluded. Never calculate or submit a total. Creator/public DTOs expose only `deliveryFee`, `serviceFee` and `totalAmount`; raw provider rate and markup are admin-only.

For `payerType: "CREATOR"`, only the authenticated creator requests and selects quotes through the creator routes. For `payerType: "INVITED_PAYER"`, only the verified recipient uses the invitation routes with `X-Waybill-Access-Token`; creator quote calls return `403`. Recipient quote access requires an active verified `PARTY_COMPLETION` invitation targeting `RECIPIENT`, `canPay: true`, completed recipient details, and the matching invited-payer waybill. The backend then runs the same pricing core: it validates both addresses, creates or reuses a Terminal quick-shipment draft, and returns available rates sorted by `deliveryFee`, reusing a nonempty cache of unexpired `AVAILABLE` rates.

Representative exact quote:

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

Unlike escrow-order quotes, waybill quotes expose `status` and `expiresAt`. Defaults are a 15-minute quote lifetime, `1000.00` markup and `500.00` service fee. Selecting an unexpired quote marks it `SELECTED`, expires sibling quotes, freezes pricing, copies its expiry to `paymentDueAt`, and sets `AWAITING_PAYMENT/PENDING`. Re-selecting that exact selected quote is idempotent only while the quote and payment window remain unexpired; an expired replay returns `400` and must requote.

Disable checkout when the client clock reaches `paymentDueAt`, but treat the server as authoritative. To recover an expired selection, call the same actor-specific quote endpoint again. If no captured or unreconciled success exists, the backend expires the selected quote, marks open attempts `SUPERSEDED`, clears frozen pricing, returns to `AWAITING_QUOTE/NOT_STARTED` and obtains new rates. Captured evidence instead moves to `PAYMENT_REVIEW/REVIEW` and the quote call returns `400`; refresh details and show support state.

### Paystack Checkout

Creator checkout is valid only when `payerType === "CREATOR"`. Invitation checkout requires a verified active invitation with `payerType === "INVITED_PAYER"` and `canPay === true`. Both require a stable `Idempotency-Key` and have no request body.

Representative exact response:

```json
{
  "waybillPublicId": "c6c39335-d5c8-462e-b24f-34819c960c31",
  "paymentAttemptId": 72,
  "payerType": "INVITED_PAYER",
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

The backend persists `INITIALIZING` and the unique reference before calling Paystack. Creator checkout replays that exact attempt for the same idempotency key. Invited checkout replays it only while the verified invitation still permits payment; after matched funding, another public checkout call returns `403`, so refresh `/status` instead. While `INITIALIZING`, `INITIALIZATION_UNKNOWN` or `INITIALIZED` is open, a different key returns `400`; retry only with the original key. A confirmed `FAILED` or `ABANDONED` attempt permits a new key while the quote remains valid.

Provider timeout or malformed initialization returns `attemptStatus: "INITIALIZATION_UNKNOWN"`, with nullable `authorizationUrl` and `accessCode`; do not attempt navigation when either required UI value is absent. The scheduler verifies the reference server-to-server. The current backend builds Paystack's callback as `${APP_BASE_URL}/waybills/{publicId}`, not from `FRONTEND_BASE_URL`. That redirect is never payment proof and is not a dedicated frontend callback contract; preserve local invitation/creator state and refresh the API after returning to the application.

Only a signed Paystack webhook or authenticated server verification can capture payment. The exact classifications are:

| Classification | UI/backend meaning |
|---|---|
| `MATCHED` | Exact NGN total, before deadline, safe state; payment confirms once and booking starts. |
| `UNDERPAID` | Full captured amount is refunded; never show partial funding. |
| `OVERPAID` | Full captured amount is refunded; ShopSecure does not retain the excess. |
| `LATE` | Expired, superseded or cancellation/refund-context success is fully refunded. |
| `DUPLICATE` | A second successful attempt is fully refunded without changing the funded root. |
| `FAILED` | Invalid/inconsistent success or a non-success provider event; safe captured money is refunded, unsafe evidence requires review. |

`PENDING` and unknown provider outcomes remain open for scheduled verification. Receipt event keys deduplicate callbacks, and `SUPERSEDED` attempts remain reconciled so late captures are found. Never call a wallet or DVA endpoint, and never mark payment from a browser redirect.

### Terminal Booking And Tracking

An exact matched payment commits before asynchronous Terminal booking. The backend first tracks the persisted quick-shipment draft to recover from a prior ambiguous pickup request. It accepts an already-active shipment instead of booking twice, accepts an already-delivered shipment as complete, checks Terminal wallet liquidity against the raw provider rate, then arranges pickup with the selected provider rate.

Booking state is persisted. Failed attempts set `SHIPPING_FAILED`, `nextBookingAttemptAt` and linear backoff of `WAYBILL_BOOKING_RETRY_DELAY * attempt`; the scheduler retries bounded batches. Five attempts by default, or a provider-terminal/unsafe draft, ends in `MANUAL_REVIEW`. Active booking sets `READY_FOR_DELIVERY`.

The first committed `READY_FOR_DELIVERY` transition persists one pickup-stage email for each party. Delivery waits for Terminal `CONFIRMED` or `IN_TRANSIT`; queued or processing (`PENDING`) bookings continue polling. Both messages include the stored tracking reference and validated HTTPS tracking URL. If Terminal did not return the URL with booking, the notification worker refreshes tracking itself and validates the exact provider shipment ID before sending. Dispatch runs after commit with a stable Resend idempotency key and retries per recipient from the V15 outbox; recoverable shipping failures defer messages, while cancellation, refund, or completion skips them. Frontend code must not call a notification endpoint or infer email delivery from the Paystack redirect. Continue showing `shipment.trackingUrl` in the UI independently of email delivery.

Only the creator can call `GET /waybills/{publicId}/shipping/tracking`; it calls Terminal and returns the exact `WaybillShipmentResponse`. Invitation holders receive stored shipment data in `/status` and have no provider-refresh route. Provider updates are timestamp-monotonic. Terminal `DELIVERED` sets `completedAt` and `COMPLETED` automatically; do not render a confirm-delivery button or settlement progress.

Treat `trackingUrl`, carrier logos, provider IDs/references and failure reasons as untrusted operational data. Open external tracking with `noopener noreferrer` and redact it from telemetry.

### Cancellation And Refunds

Cancellation is creator-only and requires a stable idempotency key plus a JSON body; send `{}` when no reason is supplied. `reason` is optional and at most 500 characters. Eligible root states are `AWAITING_PARTY_DETAILS`, `AWAITING_QUOTE`, `AWAITING_PAYMENT`, `PAYMENT_REVIEW`, `SHIPMENT_BOOKING`, `READY_FOR_DELIVERY`, `SHIPPING_FAILED`, and a narrow `MANUAL_REVIEW` case where payment is confirmed and the persisted shipment remains cancellable. In-transit/delivered shipments and completed/refunded roots cannot be cancelled.

The first request persists the key, normalized reason and timestamp, revokes invitations, and enters `CANCELLATION_PENDING` before calling Terminal. If a shipment exists, the backend persists `cancellationAttempt` and `nextCancellationAttemptAt` before each call. The scheduler retries only due `DRAFT`, `PENDING` or `CONFIRMED` shipments in bounded batches with linear `WAYBILL_CANCELLATION_RETRY_DELAY * attempt` backoff. Reusing the same key returns current state and retries only when due; a different key returns `400`. Default exhaustion is five attempts, then `MANUAL_REVIEW`.

Representative exact pending response with no refund yet:

```json
{
  "waybillPublicId": "c6c39335-d5c8-462e-b24f-34819c960c31",
  "status": "CANCELLATION_PENDING",
  "paymentStatus": "NOT_STARTED",
  "reason": "Delivery no longer required",
  "cancellationRequestedAt": "2026-08-15T11:00:00",
  "refund": null
}
```

After safe carrier cancellation, no captured payment produces `CANCELLED`. Every safely reconciled capture is queued as one full Paystack refund per payment attempt. A root refund moves through `REFUND_PENDING/REFUND_PENDING`; provider `PROCESSED` produces `REFUNDED/REFUNDED`. Provider `FAILED`, needs-attention, an unresolved dispatch with no provider refund ID after one hour by default, payment mismatch, unsafe carrier state or delivery during/after cancellation produces `MANUAL_REVIEW/REVIEW`. A duplicate-payment refund does not change an already-funded root. There is no frontend or admin refund mutation and no partial-refund body.

`WaybillDetailsResponse` does not embed refund records, and cancellation revokes invitation status access. Poll creator detail for the root `status`/`paymentStatus`. Preserve the initial cancellation response; if the creator needs refreshed refund fields, replay the cancellation route with the original idempotency key and body. That replay returns the current refund DTO and can perform a carrier retry only when its persisted backoff is due, so do not poll the POST aggressively. Admin detail is the only read DTO that embeds refund history.

Refund DTO status values are `REQUESTED`, `DISPATCHING`, `DISPATCH_UNKNOWN`, `PENDING`, `PROCESSING`, `PROCESSED`, `FAILED`, and `MANUAL_REVIEW`. Show processing for `REQUESTED` through `PROCESSING`, success for `PROCESSED`, and support state for `FAILED` or `MANUAL_REVIEW`. Do not tell the user to call Paystack or retry a refund.

### Waybill State Matrix

| `WaybillStatus` | Frontend behavior |
|---|---|
| `AWAITING_PARTY_DETAILS` | Creator can share/reissue/cancel; party-completion invitee can submit details and verify. |
| `AWAITING_QUOTE` | Creator requests and selects quotes; invited payer waits. |
| `AWAITING_PAYMENT` | Show checkout only to the configured creator or verified invited payer until `paymentDueAt`. |
| `PAYMENT_REVIEW` | Hide checkout and show reconciliation/support state. |
| `SHIPMENT_BOOKING` | Poll details/status; no retry button. |
| `READY_FOR_DELIVERY` | Show stored tracking; only creator can force provider refresh. |
| `SHIPPING_FAILED` | Show automatic booking recovery/support; creator may cancel when carrier state is safe. |
| `CANCELLATION_PENDING` | Hide payment and wait for persisted carrier/payment reconciliation. |
| `REFUND_PENDING` | Show full-refund processing; no user action. |
| `COMPLETED` | Carrier delivery completed the waybill; no confirmation or settlement UI. |
| `CANCELLED` | No captured root payment required a refund. |
| `REFUNDED` | Root full refund was processed. |
| `MANUAL_REVIEW` | Automation stopped on ambiguity/exhaustion; show support only. |

`WaybillPaymentStatus` behavior:

- `NOT_STARTED`: no quote is frozen.
- `PENDING`: selected quote is payable until `paymentDueAt`.
- `CONFIRMED`: exact payment matched; hide checkout.
- `REFUND_PENDING`: root refund is in progress.
- `REFUNDED`: root refund processed.
- `REVIEW`: payment/refund evidence needs operations review.

Attempt states are `INITIALIZING`, `INITIALIZATION_UNKNOWN`, `INITIALIZED`, `SUCCESS`, `FAILED`, `ABANDONED`, and `SUPERSEDED`. Quote states are `AVAILABLE`, `SELECTED`, `USED`, and `EXPIRED`. Shipment states are `DRAFT`, `PENDING`, `CONFIRMED`, `IN_TRANSIT`, `DELIVERED`, `CANCELLED`, `FAILED`, and `UNKNOWN`.

### Admin Waybill Reads

The two admin routes use the normal admin JWT and no request body:

| Method | Route | Success |
|---|---|---|
| `GET` | `/api/admin/waybills?creatorId=&status=&paymentStatus=&page=0&size=20` | `200 PageResponse<AdminWaybillSummaryResponse>` |
| `GET` | `/api/admin/waybills/{waybillId}` | `200 AdminWaybillDetailResponse` |

Filters are optional and combined with AND. Omit empty filters. Admin pages are zero-based, use `size=1..100`, and sort by numeric ID descending. Detail uses numeric `waybillId`, not `publicId`, and embeds at most the newest 100 quotes, attempts, receipts, refunds, invitations and history entries; parties are role-ordered. Every response has `Cache-Control: no-store` and `Pragma: no-cache`.

Admin details expose raw provider delivery fee/markup, durable booking/cancellation counters and next-attempt times, payment classifications and refund lifecycle. They still omit street/postal addresses, tracking URL, carrier logo, provider rate IDs, raw invitation/access hashes, OTP challenge ID, checkout URL/access code, idempotency keys and request hash. Use the exact `AdminWaybillDetailResponse` interface above and `ADMIN_API.md`; never add mutation controls.

## Error Contract

Application-handled error:

```json
{
  "status": 400,
  "error": "Validation failed",
  "details": {
    "creatorAddress.country": "Only Nigerian shipping addresses are supported"
  },
  "timestamp": "2026-08-07T12:00:00.123"
}
```

`details` is omitted when unavailable.

| Status | Meaning |
|---|---|
| `400` | Validation, malformed JSON, missing required header, OTP error, invalid state, or provider/business error. |
| `401` | Invalid login credentials or some authentication failures. |
| `403` | Wrong owner/role, invalid guest token, unverified login, or some JWT failures. |
| `404` | Missing order, share link, quote, shipment, user, or related resource. |
| `429` | HTTP rate limiter only. |
| `500` | Unhandled backend failure. |

Important frontend handling:

- There is no `409` business-error contract.
- Provider outages commonly appear as `400`.
- Missing `X-Order-Access-Token` or `Idempotency-Key` is `400`.
- Invalid guest token is `403`.
- Unknown, expired or revoked raw waybill invitation tokens are `404` on summary/onboarding/OTP/recovery routes.
- Invalid, expired, rotated or mismatched waybill invitation/access credentials are `403` on access-protected status/checkout routes.
- Missing `X-Waybill-Access-Token` or a required waybill `Idempotency-Key` is `400`.
- Security-layer `401`/`403` bodies may not match the standard schema.
- `429` body does not match the standard schema.
- Parse errors defensively when `status`, `details`, or `timestamp` is absent.

On member `401` or `403`, probe `/api/users/me`. Clear the JWT only if `/me` also fails; an ownership `403` should not log the user out.

On guest `403`, preserve the member session and offer guest access recovery.

## Mutation Retry Rules

| Mutation | Safe frontend behavior after timeout |
|---|---|
| Register | Do not resubmit immediately. Keep the challenge and offer resend after cooldown. |
| Verify email | If the token response was lost after success, sign in normally. |
| Forgot password | Show the same generic confirmation for every accepted request. |
| Reset password | Do not reuse a successful code; return to login. |
| Submit guest details | Wait for cooldown, then resubmit if still awaiting counterparty. |
| Verify counterparty OTP | If token response was lost after success, use access recovery. |
| Resend OTP | Replace challenge ID only after a successful response. |
| Request quotes | Refresh state, retry only while still awaiting shipping rate. |
| Select quote | Refresh state before retry; infer success from `AWAITING_PAYMENT`. |
| Pay from wallet | Reuse the same key after a timeout; after an explicit insufficient response and later top-up, use a new key for the intentional retry. |
| Initialize checkout | Repeat the same endpoint with the same guest token; an active session is replayed. Do not initialize directly with Paystack. |
| Cancel order | Refresh after ambiguous result. |
| Confirm delivery from tracking link | Reuse the same `Idempotency-Key` after every timeout; never create a second key unless the first request definitively failed before acceptance. |
| Request legacy delivery OTP | Only when `escrowReleasePolicy === "BUYER_DELIVERY_CONFIRMATION"`; call again only after cooldown. |
| Confirm legacy delivery | Only for the legacy policy; reuse the same `Idempotency-Key` for every retry. |
| Create waybill | Reuse the same key with the identical normalized body; a replay returns `201`, `replayed: true`, and no invitation link. |
| Reissue waybill invitation | Refresh creator detail after timeout before reissuing again; each success revokes the prior link/token. |
| Submit waybill party details or payer claim | Wait for the OTP cooldown before retrying; do not switch claim kind for the same invitation. |
| Verify waybill invitation OTP | If the token response was lost, request access recovery rather than resubmitting a consumed code. |
| Resend/recover waybill access | Replace challenge/access token only after a successful response; recovery rotates the previous access token. |
| Request waybill quotes | Reuse cached results while valid; after expiry retry only through the creator route and then refresh state. |
| Select waybill quote | Re-selecting the same selected quote is safe; otherwise refresh details before retrying. |
| Initialize waybill checkout | Reuse the same key while an attempt is open; after invited funding refresh status instead because payment access is closed. Use a new key only after explicit `FAILED`/`ABANDONED` and before expiry. |
| Refresh waybill tracking | A normal GET retry is safe, but back off provider errors. |
| Cancel waybill | Reuse the same key and original reason; persisted scheduler retries carrier cancellation when due. |

Do not create frontend retry buttons for backend shipping booking, payout dispatch, waybill payment reconciliation, carrier cancellation, or refunds.

## Security And Privacy Checklist

- Never call Paystack APIs, Terminal Africa, Twilio, or Resend directly from the browser. Navigating to the backend-issued Paystack `authorizationUrl` is expected.
- Never expose provider secret keys.
- Never put JWT, guest token, OTP, challenge ID, or bank details in a URL.
- Never persist signup/reset codes or passwords. Keep signup challenge metadata in `sessionStorage` only.
- Redact share tokens and complete `shareableLink` values from analytics, logs, session replay, and error reports.
- Use `Referrer-Policy: no-referrer` on the share page.
- Use `cache: "no-store"` for private and guest-order requests.
- Do not persist order lists containing guest PII.
- Keep raw guest bank account only in form memory until submission.
- Use `autocomplete="one-time-code"` for OTP.
- Open tracking links with `noopener noreferrer`.
- Treat carrier logos as untrusted remote images.
- Do not send member JWT on public guest requests.
- Do not treat a guest token as a member session.
- Treat the complete waybill invitation URL as a bearer secret and never persist the raw token outside the current invitation flow.
- Keep waybill access tokens session-scoped by `waybillPublicId`; recovery, cancellation and reissue revoke older access.
- Use `cache: "no-store"` for every invitation request and honor backend `Cache-Control`, `Pragma` and `Referrer-Policy` headers.
- Never persist checkout `authorizationUrl`, `accessCode`, invitation contact staging data, creator waybill parties or cancellation/refund failure reasons.
- Creator waybill list/detail responses contain unmasked party contacts, full street/postal addresses and staged invitation contact. Request them with browser caching disabled and do not put them in persisted client/query state.
- Treat every admin response as sensitive operational data even though high-risk fields are masked or omitted.
- Never persist admin responses in browser storage, service-worker caches, persisted query caches, analytics, session replay, support widgets, or error reports.
- Redact exact-email admin filters, provider references, tracking references, failure reasons, and all IDs from third-party browser telemetry.
- Do not rely on a hidden `/admin` route as authorization; require `role === "ADMIN"` in navigation and let the backend enforce every request.

## Known Frontend Contract Limitations

These are implemented API limitations, not frontend bugs:

1. Creator order details do not return cumulative direct-payment `amountReceived`; this affects migrated `DIRECT_DVA` orders, not wallet or checkout matching.
2. Guest status does not return full pricing breakdown after refresh, only expected and received totals.
3. There is no refresh-token, logout, SSE, or WebSocket endpoint.
4. `PAYMENT_FAILED`, `SHIPPING_FAILED`, `PAYOUT_FAILED`, and `MANUAL_REVIEW` have no frontend mutation for manual recovery.
5. `Retry-After` is not exposed to cross-origin JavaScript.
6. Reusable virtual-account matching limitations remain only for migrated `DIRECT_DVA` orders.
7. The admin API is read-only except for version-fenced Chowdeck control updates; it has no admin-account-management endpoints.
8. Dashboard aggregates can be up to 30 seconds old and have no force-refresh parameter.
9. Admin user email filtering is exact-match only; there is no fuzzy search or cross-resource search endpoint.
10. Admin order detail embeds at most the latest 100 attempts, receipts, and history records per collection.
11. A separately hosted admin frontend origin is not currently in the backend CORS allowlist.
12. Admin wallet-transaction rows do not include currency; the frontend must not assume one in the global ledger.
13. Paystack waybill checkout currently uses `${APP_BASE_URL}/waybills/{publicId}` as its callback, not a dedicated `FRONTEND_BASE_URL` return route. The redirect itself is never payment proof.
14. Invitation holders have no live provider-tracking refresh route; only the creator can refresh Terminal tracking.
15. There is no creator/public/admin refund, booking-retry, cancellation-retry, reclassification, completion, or manual-review mutation for waybill operations.
16. Creator waybill detail exposes only root refund state, not a refund object; current refund fields are available from an original-key cancellation replay or read-only admin detail.

## Removed Old Contract

Do not use or retain frontend code for:

- `POST /orders/{orderId}/pay`
- `/vendor-info`
- `fullName` counterparty field
- Free-form country names
- Buyer/vendor role during registration
- WhatsApp OTP
- Available and processing wallet balances
- Paginated wallet transaction responses
- New vendor orders paid by direct transfer to the vendor's reusable DVA
- `CREATED`, `VENDOR_INFO_COMPLETE`, or `PAID_ESCROW` order states
- Raw resolved account number in resolve response
- Guest delivery confirmation with `code` but no `challengeId`

Legacy workflow orders are migrated to `MANUAL_REVIEW` and cannot resume the new flow.

## Frontend Delivery Checklist

- Configure API base as `http://localhost:8080` locally.
- Keep frontend `APP_BASE_URL` at `http://localhost:3000` locally.
- Restore members with `/api/users/me`.
- Require signup email verification before saving a member session.
- Add generic forgot-password and code-based reset screens.
- Add profile completion for legacy users.
- Use role selection only on order creation.
- Add ISO-2 country and postal-code fields.
- Add parcel and vendor-bank fields to complete member order creation for both creator roles.
- Add public bank listing and member bank resolution; never collect bank details in the guest payment flow.
- Add guest OTP resend and access recovery.
- Store guest token per order in session storage.
- Add creator-controlled shipping quote and selection UI for both order roles.
- Render all pricing from selection/detail responses.
- Branch payment UI on `fundingMode`.
- Add idempotent wallet payment, exact-shortfall top-up instructions, wallet refresh, and manual retry for buyer-created orders.
- Add guest checkout initialization, redirect to `authorizationUrl`, callback return handling, and webhook-status polling for vendor-created orders.
- Never treat a DVA top-up as automatic order payment.
- Add stable tracking plus buyer/vendor pickup and settlement messaging.
- Branch on `escrowReleasePolicy`; show public tracking-link confirmation for `BUYER_DELIVERY_CONFIRMATION`, hide it for `CARRIER_PICKUP`, and preserve one stable confirmation key per order.
- Add wallet refresh and bare-array ledger handling.
- Add UI states for underpayment, overpayment, shipping recovery, payout intervention, and manual review.
- Remove legacy pay, WhatsApp, escrow, and dual-balance UI.
- Redact all sensitive order/share data from observability.
- Add an `/admin` route guard using the restored `MemberRole`, while preserving backend authorization as the source of truth.
- Add the admin overview, user, order, wallet-ledger, withdrawal, settlement, shipment, payment-attempt, and payment-receipt read screens.
- Use the documented admin filters, zero-based page envelope, sparse metric-map fallbacks, and 30-second dashboard refresh floor.
- Keep resource-management admin screens read-only; expose only the documented version-fenced Chowdeck control mutation.
- Disable browser and persisted query caching for all admin data and redact operational identifiers from frontend telemetry.
- Add creator waybill create/list/detail/history, invitation reissue, creator-paid quote selection, creator checkout, tracking and idempotent cancellation screens.
- Implement only the two payer-driven create shapes: creator-paid with complete `oppositeParty`, or sender-created invited-payer with no `oppositeParty`.
- Add the public recipient `PARTY_COMPLETION` branch, OTP resend, access recovery, recipient quote retrieval/selection and rotated session-scoped access tokens.
- Build waybill invitation URLs from the backend-returned `invitationLink`; configure the deployed backend `FRONTEND_BASE_URL` to the matching frontend origin.
- Render waybill pricing and quote expiry only from backend fields; exclude `declaredValue` from payable total and requote through the same creator or recipient route after expiry.
- Add creator/invited Paystack Checkout with stable idempotency keys, nullable initialization-unknown URLs and backend-status polling; never use wallet/DVA UI.
- Add automatic Terminal-completion, shipping-recovery, persisted cancellation, refund and manual-review states with no user retry/confirmation/settlement controls.
- Add admin waybill list/detail reads using numeric detail IDs, exact filters, nested limits, masking and no-store behavior.
- Redact raw invitation URLs, access/OTP/checkout credentials, party PII, provider references and failure reasons from all frontend telemetry and persistence.

## Backend-Only Routes

The browser must never call:

```text
POST /api/webhooks/paystack
POST /api/webhooks/terminal
POST /api/webhooks/chowdeck
```

Paystack and Terminal require provider HMAC signatures over the exact raw body. Chowdeck does not publish a callback signature: its callback body is never trusted for status or payout and only prompts the backend to fetch the exact persisted delivery through the authenticated Chowdeck API. Only that canonical response can record pickup and release the item amount. These routes are provider-only and must never be called by frontend code.

## Verification

The current backend test suite passes with no failures or errors. Coverage includes both order-role workflows, wallet-first funding and idempotency, order-specific checkout initialization, webhook matching and verification recovery, equal-value concurrent orders, failed/replacement/late checkout attempts, duplicate-safe DVA top-ups, guest access recovery, shipping recovery, delivery settlement, wallet release, payout retries/reversal, V14 waybill creator/invitation/checkout/shipping/cancellation/refund flows, signed provider webhooks, unsigned Chowdeck canonical reconciliation, admin role enforcement, admin masking, dashboard metrics, filters, pagination, and route security.
