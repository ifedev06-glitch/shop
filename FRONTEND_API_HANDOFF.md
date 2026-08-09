# ShopSecure Frontend API Handoff

Last updated: 2026-08-08

## Contract Status

This document describes the backend contract currently implemented in the ShopSecure working tree. The guest, shipping, wallet-first buyer payment, order-specific guest checkout, delivery-confirmation, payout, and wallet-release flows are implemented.

The frontend must be released with the matching backend code and Flyway V9 migration. There is no API version prefix, so an old frontend cannot safely use the new backend contract.

Important changes from the previous handoff:

- All routes previously marked `TO BUILD` for this workflow now exist.
- `POST /orders/{orderId}/pay` remains removed. Buyer-created orders now use the explicit `POST /orders/{orderId}/payments/wallet` mutation.
- Shipping quote selection is mandatory before payment.
- New buyer-created orders are wallet funded; new vendor-created orders use a unique Paystack Checkout session for the guest buyer.
- DVA top-ups credit the member wallet but never automatically pay an order. The registered buyer manually retries wallet payment.
- Personal DVAs remain available to every registered user and are not used for new guest-order checkout matching.
- A buyer pays item amount, customer delivery fee, and service fee.
- A seller receives item amount only.
- Guest access uses an order-scoped token, not a member JWT.
- Buyer and vendor are roles selected per order, not permanent account roles.
- Addresses now require an ISO-2 country code and postal code.
- Guest vendors must provide both payout-bank and parcel details.
- Wallet transactions return a bare array, not a paginated response.
- Business and invalid-state errors return `400`, not `409`.

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
APP_BASE_URL=http://localhost:3000
```

`APP_BASE_URL` is used to create `shareableLink` and the Paystack Checkout return URL. It is a frontend origin, not the backend API origin.

Local provider behavior:

- Terminal Africa is used only when `TERMINAL_AFRICA_ENABLED=true`; adding a key alone does not enable it.
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

Wallet payment and delivery confirmation also require:

```http
Idempotency-Key: <stable-value-for-this-mutation-attempt>
```

The idempotency key can be any nonblank value up to 255 characters. A UUID is recommended. Reuse the same value after an ambiguous timeout. Delivery confirmation always reuses one stable key; a new intentional wallet-payment attempt after an explicit insufficient response should use a new key.

Serialization rules:

- Money is returned as decimal JSON numbers.
- Do not calculate money with JavaScript binary floating point.
- IDs are JSON integers.
- Enum values are uppercase and case-sensitive.
- `Instant` values are ISO-8601 timestamps, normally ending in `Z`.
- `LocalDateTime` fields such as order `createdAt` have no offset.
- Nullable response fields are normally included as `null`.
- All collection routes return bare arrays. No route is currently paginated.

## CORS And Rate Limits

Allowed browser origins:

```text
http://localhost:3000
https://www.shopsecure.app
```

Not currently allowed:

```text
http://127.0.0.1:3000
http://localhost:5173
https://shopsecure.app
```

Allowed methods are `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, and `OPTIONS`. All request headers are allowed and credentials are enabled.

Rate limits apply to frontend routes:

- Per member or source IP: burst 10, then approximately 2 requests per second.
- Global: burst 100, then approximately 50 requests per second.
- Webhooks are exempt.

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

export type OrderStatus =
  | "AWAITING_COUNTERPARTY"
  | "AWAITING_SHIPPING_RATE"
  | "AWAITING_PAYMENT"
  | "SHIPMENT_BOOKING"
  | "READY_FOR_DELIVERY"
  | "PAYOUT_PENDING"
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
  | "ORDER_PAYMENT_DEBIT";
```

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
  parcel?: OrderParcelRequest;
}

export interface ResolveBankAccountRequest {
  accountNumber: string;
  bankCode: string;
}

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
}

export interface ShippingSelectionResponse {
  status: OrderStatus;
  fundingMode: OrderFundingMode;
  selectedQuote: ShippingQuoteResponse;
  pricing: PricingBreakdownResponse;
  paymentInstructions: PaymentInstructionsResponse | null;
}

export interface ShippingStatusResponse {
  status: ShipmentStatus;
  carrierName: string | null;
  trackingReference: string | null;
  trackingUrl: string | null;
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
  status: OrderStatus;
  shareToken: string;
  shareableLink: string;
  createdAt: string;
}

export interface OrderDetailsResponse {
  id: number;
  itemDescription: string;
  itemAmount: number;
  currency: string;
  creatorRole: OrderPartyRole;
  counterpartyRole: OrderPartyRole;
  fundingMode: OrderFundingMode;
  status: OrderStatus;
  paymentStatus: PaymentStatus;
  settlementStatus: SettlementStatus;
  pricing: PricingBreakdownResponse | null;
  paymentInstructions: PaymentInstructionsResponse | null;
  counterparty: CounterpartyResponse | null;
  parcel: ParcelResponse | null;
  shipping: ShippingStatusResponse | null;
  fundedAt: string | null;
  deliveryConfirmedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PublicOrderResponse {
  itemAmount: number;
  itemDescription: string;
  currency: string;
  status: OrderStatus;
  creatorRole: OrderPartyRole;
  counterpartyRole: OrderPartyRole;
  counterpartyVerified: boolean;
  paymentStatus: PaymentStatus;
  settlementStatus: SettlementStatus;
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
  amountExpected: number | null;
  amountReceived: number | null;
  currency: string;
  paymentInstructions: PaymentInstructionsResponse | null;
  shipping: ShippingStatusResponse | null;
  fundedAt: string | null;
  deliveryConfirmedAt: string | null;
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
```

## Validation Rules

### Registration

- `firstname` and `lastname`: required, maximum 100 characters.
- `email`: required and valid.
- `phoneNumber`: 7 to 15 digits; registration does not accept `+`.
- `password` / `newPassword`: 8 to 72 characters with uppercase, lowercase, digit, and special character.

### Profile Update And Guest Phone

- Phone may have an optional leading `+` followed by 7 to 15 digits.
- Email cannot be changed through profile update.

### Address

- `line1`: required, maximum 255.
- `line2`: optional, maximum 255.
- `city` and `state`: required, maximum 100.
- `country`: exactly two letters, for example `NG`; never send `Nigeria`.
- `postalCode`: required, maximum 32.

### Parcel

- `weightKg`: required, minimum `0.001`, up to 3 decimal places.
- Dimensions are optional.
- Supplied dimensions must be at least `0.01` and have at most 2 decimal places.

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
| `GET` | `/api/banks` | Public | `200` | `BankResponse[]` |
| `POST` | `/api/bank-accounts/resolve` | Member | `200` | `ResolvedBankAccountResponse` |
| `GET` | `/api/wallet` | Member | `200` | `WalletResponse` |
| `GET` | `/api/wallet/transactions` | Member | `200` | `WalletTransactionResponse[]` |
| `POST` | `/orders` | Member | `201` | `CreateOrderResponse` |
| `GET` | `/orders` | Member | `200` | `OrderDetailsResponse[]` |
| `GET` | `/orders/{orderId}` | Owner | `200` | `OrderDetailsResponse` |
| `GET` | `/orders/{orderId}/history` | Owner | `200` | `OrderStatusHistoryResponse[]` |
| `POST` | `/orders/{orderId}/shipping/quotes` | Buyer creator | `200` | `ShippingQuoteResponse[]` |
| `PUT` | `/orders/{orderId}/shipping/selection` | Buyer creator | `200` | `ShippingSelectionResponse` |
| `POST` | `/orders/{orderId}/payments/wallet` | Buyer creator | `200` | `OrderWalletPaymentResponse` |
| `GET` | `/orders/{orderId}/shipping/tracking` | Owner | `200` | `ShippingStatusResponse` |
| `POST` | `/orders/{orderId}/shipping/cancel` | Owner | `200` | `ShippingStatusResponse` or empty |
| `POST` | `/orders/{orderId}/confirm-delivery` | Buyer creator | `200` | `DeliveryConfirmationResponse` |
| `GET` | `/orders/token/{shareToken}` | Public | `200` | `PublicOrderResponse` |
| `POST` | `/orders/token/{shareToken}/bank-account/resolve` | Public guest vendor | `200` | `ResolvedBankAccountResponse` |
| `POST` | `/orders/token/{shareToken}/counterparty-info` | Public | `202` | `OtpChallengeResponse` |
| `POST` | `/orders/token/{shareToken}/verify-otp` | Public | `200` | `OrderVerificationResponse` |
| `POST` | `/orders/token/{shareToken}/resend-otp` | Public | `200` | `OtpChallengeResponse` |
| `POST` | `/orders/token/{shareToken}/access-recovery-otp` | Public | `200` | `OtpChallengeResponse` |
| `POST` | `/orders/token/{shareToken}/recover-access` | Public | `200` | `OrderVerificationResponse` |
| `POST` | `/orders/token/{shareToken}/shipping/quotes` | Guest buyer | `200` | `ShippingQuoteResponse[]` |
| `PUT` | `/orders/token/{shareToken}/shipping/selection` | Guest buyer | `200` | `ShippingSelectionResponse` |
| `POST` | `/orders/token/{shareToken}/payments/checkout` | Guest buyer | `200` | `OrderCheckoutResponse` |
| `GET` | `/orders/token/{shareToken}/status` | Verified guest | `200` | `GuestOrderStatusResponse` |
| `POST` | `/orders/token/{shareToken}/delivery-otp` | Guest buyer | `200` | `OtpChallengeResponse` |
| `POST` | `/orders/token/{shareToken}/confirm-delivery` | Guest buyer | `200` | `DeliveryConfirmationResponse` |
| `POST` | `/api/webhooks/paystack` | Backend only | `200` | Empty |
| `POST` | `/api/webhooks/terminal` | Backend only | `200` | Empty |

## Authentication And Profile

### Register

`POST /api/auth/register`

```json
{
  "firstname": "Ada",
  "lastname": "Okafor",
  "email": "ada@example.com",
  "phoneNumber": "2348012345678",
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

### Resolve For A Guest Vendor

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

## Create And Read Orders

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

The create response is the only API response that includes `shareToken` and `shareableLink`. `GET /orders` and `GET /orders/{id}` do not return either value. Preserve the create response securely if the creator needs to share the link again.

### List Creator Orders

`GET /orders`

Returns a bare array, newest first. Each entry is a full `OrderDetailsResponse` and can include counterparty PII. Do not persist this response in long-lived browser storage.

### Private Order Details

`GET /orders/{orderId}`

Only the creator can read it.

Payment-instruction visibility:

- `fundingMode: "WALLET"`: creator details return `paymentInstructions: null`. Top-up instructions are returned only by an insufficient wallet-payment attempt.
- `fundingMode: "PAYSTACK_CHECKOUT"`: creator details return `paymentInstructions: null`; only the verified guest buyer can initialize checkout.
- `fundingMode: "DIRECT_DVA"`: legacy only. Existing private visibility rules remain for already-issued transfer instructions.
- Branch on `fundingMode`; do not infer funding behavior only from creator role or payment status.

### Order History

`GET /orders/{orderId}/history`

Returns oldest first. Use it for a timeline, but use the order response as the current source of truth.

## Public Guest Onboarding

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

### Request Quotes As Buyer Creator

`POST /orders/{orderId}/shipping/quotes`

No request body.

### Request Quotes As Guest Buyer

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
- Checkout payment funds the order but does not credit the vendor wallet yet. The item amount is credited only after buyer delivery confirmation.

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

Carrier `DELIVERED` never releases seller money automatically. Only explicit buyer confirmation settles an order.

An eligible shipment has one of:

```text
PENDING
CONFIRMED
IN_TRANSIT
DELIVERED
```

Frontend confirmation gate:

```ts
const canConfirmDelivery =
  order.status === "READY_FOR_DELIVERY" &&
  order.shipping != null &&
  ["PENDING", "CONFIRMED", "IN_TRANSIT", "DELIVERED"].includes(
    order.shipping.status,
  );
```

### Buyer-Created Order

The authenticated creator is the buyer.

`POST /orders/{orderId}/confirm-delivery`

```http
Authorization: Bearer <jwt>
Idempotency-Key: <stable-uuid>
Content-Type: application/json
```

```json
{
  "confirmation": true
}
```

The backend transfers item amount only to the verified guest vendor payout account.

After Paystack confirms payout success, the backend sends both participants email and SMS. The vendor is told that the buyer confirmed delivery and the verified bank account was credited. The buyer is thanked for confirming delivery and told that the vendor was credited. These notifications are not sent while payout is pending or unknown.

Possible successful response states:

- `PAYOUT_PENDING` / `PENDING`
- `PAYOUT_PENDING` / `DISPATCH_UNKNOWN`
- `PAYOUT_PENDING` / `REQUIRES_ACTION`
- `PAYOUT_FAILED` / `FAILED`
- `COMPLETED` / `COMPLETED`

Do not show completion until settlement status is `COMPLETED`.

### Vendor-Created Order

The verified guest is the buyer.

Request delivery OTP:

`POST /orders/token/{shareToken}/delivery-otp`

Requires guest token and no request body.

Confirm delivery:

`POST /orders/token/{shareToken}/confirm-delivery`

```http
X-Order-Access-Token: <guest-token>
Idempotency-Key: <stable-uuid>
Content-Type: application/json
```

```json
{
  "challengeId": "delivery-challenge-id",
  "code": "123456"
}
```

The backend credits item amount only to the registered vendor wallet exactly once and normally returns `COMPLETED` / `COMPLETED`.

After the wallet credit commits, the backend sends both participants email and SMS. The registered vendor is told that the buyer confirmed delivery and the ShopSecure wallet was credited. The guest buyer is thanked for confirming delivery and told that the vendor was credited.

Same idempotency key returns the existing confirmation response. A different key after confirmation returns `400`.

## Wallet

### Current Wallet

`GET /api/wallet`

There is one balance only. Do not build available/processing balance UI.

### Wallet Ledger

`GET /api/wallet/transactions`

Returns a bare `WalletTransactionResponse[]`, newest first. `page` and `size` parameters are ignored because pagination is not implemented.

- `DVA_CREDIT`: incoming dedicated-account top-up; `orderId` is null.
- `ORDER_PAYMENT_DEBIT`: buyer-created order payment; `orderId` identifies the order.
- `ORDER_RELEASE`: vendor-created order settlement credit.
- `LEGACY_ESCROW_DEBIT`: migrated history only.

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
| `AWAITING_SHIPPING_RATE` | Buyer requests and selects quotes; creator can still cancel. |
| `AWAITING_PAYMENT` | Branch on `fundingMode`: show wallet payment/top-up UI to the registered buyer, or checkout initialization/return polling to the guest buyer. |
| `SHIPMENT_BOOKING` | Poll only; backend is booking pickup. |
| `READY_FOR_DELIVERY` | Show tracking and buyer confirmation when shipment is eligible. |
| `PAYOUT_PENDING` | Poll only; payout verification is automatic. |
| `COMPLETED` | Show success/receipt; continue to revalidate because an external payout can later reverse. |
| `PAYMENT_FAILED` | Read-only support state; no dedicated frontend recovery endpoint. |
| `SHIPPING_FAILED` | Show automatic recovery/support state; do not create a manual retry button. |
| `PAYOUT_FAILED` | Show automatic retry/support state; do not repeat delivery confirmation. |
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
| `NOT_STARTED` | Delivery has not been confirmed. |
| `PENDING` | Payout queued or dispatched; poll. |
| `DISPATCH_UNKNOWN` | Backend is verifying an ambiguous result; no user action. |
| `COMPLETED` | Settlement succeeded. |
| `FAILED` | Automated retry/support state. |
| `REVERSED` | Terminal support state for reversed external payout. |
| `REQUIRES_ACTION` | Operations intervention required; do not retry delivery confirmation. |

### Shipment State

| Status | Frontend behavior |
|---|---|
| `DRAFT` | Quote/payment stage; cannot confirm delivery. |
| `PENDING` | Provider accepted booking; show tracking where available. |
| `CONFIRMED` | Pickup booked. |
| `IN_TRANSIT` | Show in-transit tracking. |
| `DELIVERED` | Still require explicit buyer confirmation. |
| `CANCELLED` | Hide confirmation and show support/cancelled state. |
| `FAILED` | Hide confirmation and show recovery/support state. |
| `UNKNOWN` | Hide confirmation and show waiting/support state. |

## Buyer-Created Flow

Registered creator is buyer; share-link guest is vendor.

1. Member creates order with `creatorRole: "BUYER"`, buyer delivery address, and no parcel.
2. Frontend preserves and displays the create-response share link.
3. Guest vendor opens public link.
4. Guest resolves bank account, confirms account name, and submits contact, pickup address, payout bank, and parcel.
5. Guest verifies OTP and stores guest token.
6. Order becomes `AWAITING_SHIPPING_RATE`.
7. Registered buyer requests and selects shipping quote.
8. Selection response freezes total and returns `fundingMode: "WALLET"` with no direct payment instructions.
9. Buyer explicitly calls the wallet-payment endpoint with an idempotency key.
10. If sufficient, the backend debits the exact total once and starts shipping. If insufficient, frontend displays the returned shortfall and DVA top-up instructions.
11. Any valid DVA transfer credits the wallet only; after the balance refreshes, the buyer manually retries wallet payment.
12. Frontend polls until shipping is booked.
13. Vendor delivers; both parties can observe stored tracking, while creator can refresh provider tracking.
14. Registered buyer explicitly confirms delivery with a stable idempotency key.
15. Backend pays item amount to guest vendor bank.
16. Frontend polls through payout states and shows completion only after provider success.

Guest vendor never sees the buyer creator's virtual account and never confirms delivery.

## Vendor-Created Flow

Registered creator is vendor; share-link guest is buyer.

1. Member creates order with `creatorRole: "VENDOR"`, pickup address, and parcel.
2. Frontend preserves and displays the create-response share link.
3. Guest buyer opens public link.
4. Guest submits contact and delivery address only.
5. Guest verifies OTP and stores guest token.
6. Order becomes `AWAITING_SHIPPING_RATE`.
7. Guest buyer requests and selects shipping quote.
8. Selection response returns `fundingMode: "PAYSTACK_CHECKOUT"`, the frozen total, and no payment instructions.
9. Guest buyer calls the checkout initialization endpoint and is redirected to its `authorizationUrl`.
10. After Paystack returns to the share page, frontend polls guest status; it never marks payment locally.
11. A signed webhook matches the unique reference and backend books vendor pickup and buyer delivery.
12. Guest buyer tracks shipment through guest status.
13. Guest buyer requests delivery OTP and confirms with a stable idempotency key.
14. Backend credits item amount to registered vendor wallet exactly once.
15. Vendor creator refreshes wallet and ledger.

Vendor creator cannot choose shipping or call creator delivery confirmation for this order.

## Error Contract

Application-handled error:

```json
{
  "status": 400,
  "error": "Validation failed",
  "details": {
    "creatorAddress.country": "Country must be an ISO-2 country code"
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
| Request delivery OTP | Call again only after OTP cooldown. |
| Confirm delivery | Reuse the same `Idempotency-Key` for every retry. |

Do not create frontend retry buttons for backend shipping booking or payout dispatch.

## Security And Privacy Checklist

- Never call Paystack APIs, Terminal Africa, Twilio, or Resend directly from the browser. Navigating to the backend-issued Paystack `authorizationUrl` is expected.
- Never expose provider secret keys.
- Never put JWT, guest token, OTP, challenge ID, or bank details in a URL.
- Never persist signup/reset codes or passwords. Keep signup challenge metadata in `sessionStorage` only.
- Redact share tokens from analytics, logs, session replay, and error reports.
- Use `Referrer-Policy: no-referrer` on the share page.
- Use `cache: "no-store"` for private and guest-order requests.
- Do not persist order lists containing guest PII.
- Keep raw guest bank account only in form memory until submission.
- Use `autocomplete="one-time-code"` for OTP.
- Open tracking links with `noopener noreferrer`.
- Treat carrier logos as untrusted remote images.
- Do not send member JWT on public guest requests.
- Do not treat a guest token as a member session.

## Known Frontend Contract Limitations

These are implemented API limitations, not frontend bugs:

1. Share token and shareable link are returned only by create order. They cannot be recovered from order detail/list.
2. Creator order details do not return cumulative direct-payment `amountReceived`; this affects migrated `DIRECT_DVA` orders, not wallet or checkout matching.
3. Shipping quotes do not return `expiresAt`; selection may reject an expired quote and require refetching.
4. Guest status does not return full pricing breakdown after refresh, only expected and received totals.
5. Wallet transactions and order lists are unpaginated.
6. There is no refresh-token, logout, SSE, or WebSocket endpoint.
7. `PAYMENT_FAILED`, `SHIPPING_FAILED`, `PAYOUT_FAILED`, and `MANUAL_REVIEW` have no frontend mutation for manual recovery.
8. `Retry-After` is not exposed to cross-origin JavaScript.
9. Reusable virtual-account matching limitations remain only for migrated `DIRECT_DVA` orders.

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
- Add parcel fields for vendor creator and guest vendor only.
- Add public bank listing and share-scoped bank resolution.
- Add guest OTP resend and access recovery.
- Store guest token per order in session storage.
- Add buyer-controlled shipping quote and selection UI.
- Render all pricing from selection/detail responses.
- Branch payment UI on `fundingMode`.
- Add idempotent wallet payment, exact-shortfall top-up instructions, wallet refresh, and manual retry for buyer-created orders.
- Add guest checkout initialization, redirect to `authorizationUrl`, callback return handling, and webhook-status polling for vendor-created orders.
- Never treat a DVA top-up as automatic order payment.
- Add tracking and role-specific delivery confirmation.
- Reuse stable delivery-confirmation idempotency keys.
- Add wallet refresh and bare-array ledger handling.
- Add UI states for underpayment, overpayment, shipping recovery, payout intervention, and manual review.
- Remove legacy pay, WhatsApp, escrow, and dual-balance UI.
- Redact all sensitive order/share data from observability.

## Backend-Only Routes

The browser must never call:

```text
POST /api/webhooks/paystack
POST /api/webhooks/terminal
```

They require provider HMAC signatures over the exact raw body. Valid webhook events drive payment, shipping, payout, reversal, and recovery state changes.

## Verification

The backend contract is covered by the current test suite:

```text
101 tests
0 failures
0 errors
```

Coverage includes both order-role workflows, wallet-first funding and idempotency, order-specific checkout initialization, webhook matching and verification recovery, equal-value concurrent orders, failed/replacement/late checkout attempts, duplicate-safe DVA top-ups, guest access recovery, shipping recovery, delivery settlement, wallet release, payout retries/reversal, webhook signatures, and route security.
