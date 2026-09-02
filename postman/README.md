# ShopSecure Postman Collection

## Import

Import these files into Postman:

1. `ShopSecure V19 Carrier Pickup Settlement and Shipping Control.postman_collection.json`
2. `ShopSecure Local.postman_environment.json`

`ShopSecure Backend API.postman_collection.json` is retained as a legacy V15 reference for unchanged authentication, wallet, admin, and webhook utilities. Do not run its old order or waybill onboarding folders; those routes are intentionally removed in V16.

Select the **ShopSecure Local** environment and confirm `baseUrl` points to the running backend. The collection contains no real credentials.

## Required Inputs

Before registering, replace these sample values with inboxes and phone numbers you can access:

- `memberEmail` and `memberPhone`
- `guestVendorEmail` and `guestVendorPhone`
- `guestBuyerEmail` and `guestBuyerPhone`
- `waybillPartyEmail` and `waybillPartyPhone`

Set `memberPassword` if you are logging into an existing account. In Paystack test mode, the default bank pair is `001` / `0000000000`.

OTP codes are never returned by the API. Paste each code into its environment variable before continuing:

- `emailOtp`
- `buyerPaymentOtp`
- `waybillPaymentOtp`
- `accessRecoveryOtp`
- `passwordResetOtp`

Registration and password reset require working Resend configuration. Order OTPs are sent through email and SMS; the local SMS stub discards SMS, so use email unless Twilio is enabled.

## Test Flows

Authenticate through the legacy utility collection first, then run the V19 folders in order. Response scripts retain IDs, selection-generated payment tokens, custom tracking tokens, access tokens, quote IDs, checkout details, and stable idempotency keys. Neither order nor waybill creation returns a raw public token.

The final admin folder requires an `ADMIN` JWT. Its target mode defaults to `OFF`; change `chowdeckTargetMode` deliberately only after reviewing `hardKillActive`, provider credentials, and wallet readiness. A `409` requires a fresh GET and human review rather than an automatic retry.

### Buyer-Created Order

1. Authenticate a member.
2. Create the complete buyer order with the vendor, parcel, both addresses, and vendor bank account.
3. As the authenticated creator, request and select a shipping quote.
4. Pay the complete item + delivery + service total from the member wallet.
5. Read the full custom ShopSecure tracking snapshot without authentication or OTP. The same bearer link is emailed to buyer and vendor, exposes both parties and escrow details to anyone holding it, and remains stable while carrier status changes.
6. Wait for an authenticated carrier update to report `IN_TRANSIT`. The backend records pickup but keeps item funds in escrow.
7. After receipt, call `POST /orders/tracking/{trackingToken}/confirm-delivery` with `{"confirmation":true}` and one stable `Idempotency-Key`. No JWT, guest token, or OTP is required; anyone holding the shared tracking link can trigger this action.
8. Poll the order while payout is pending. Payout success completes settlement; continue showing carrier tracking if delivery status is still moving.

If wallet payment returns `INSUFFICIENT_FUNDS`, the request records `walletShortfall`, `topUpAccountNumber`, and `topUpBankName`, then stops a collection-runner sequence. Top up the displayed DVA. For a local/test backend, the signed DVA webhook helper can simulate that provider callback. A DVA credit does not automatically pay the order; rerun wallet payment after the top-up appears. The response script clears the rejected key so that retry receives a new intentional payment key.

### Vendor-Created Order

1. Authenticate a member.
2. Create the complete vendor order with the buyer, parcel, both addresses, and vendor bank account.
3. As the authenticated vendor, request and select a quote; capture the returned `buyerPaymentLink`.
4. The buyer requests `payment-access-otp`, verifies it, and initializes Paystack Checkout.
5. Complete `checkoutAuthorizationUrl` and poll status until shipping is ready.
6. Open the full custom ShopSecure tracking snapshot after confirmed payment. The creator and guest responses contain the same public bearer URL; no tracking-page authentication or OTP is required.
7. Wait for an authenticated carrier update to report `IN_TRANSIT`; pickup alone does not release escrow.
8. After receipt, call the public tracking-token confirmation mutation with a stable `Idempotency-Key`; no delivery OTP or account authentication is required.
9. Repeat the status request while the item amount is paid to the frozen vendor bank recipient. It is never credited to a ShopSecure wallet.

### Waybill

1. Create the waybill with `creatorRole`, `payerRole`, both parties, both addresses, and parcel data.
2. The creator requests and selects the quote for every payer-role combination.
3. When the creator pays, selection returns no link and creator checkout is used.
4. When the opposite party pays, selection returns a `PAYMENT_ONLY` link capped to the quote deadline.
5. The link holder requests `payment-access-otp`, verifies it, and initializes checkout. They cannot change party or quote data.
6. Reissue rotates the payment link and supersedes any checkout tied to the old link.

## Webhook Helpers

The final folder contains the unsigned Chowdeck callback helper for controlled local/test environments. Signed Paystack, local-stub, and Terminal helpers remain in the legacy utility collection. Do not send synthetic callbacks to production.

- Set `paystackWebhookSecret` to the same test key configured by the backend.
- The local stub shipping secret defaults to `local-shipping-webhook-secret`.
- Set `terminalWebhookSecret` and `terminalShipmentId` only when deliberately testing the Terminal adapter.
- Set `chowdeckWebhookReference` to an exact persisted Chowdeck `order-{id}` or `waybill-{publicId}` reference. The helper sends no signature. Its claimed status is not accepted; a `200` means the callback was acknowledged, but unknown references deliberately receive the same response. Confirm reconciliation through the order or waybill tracking endpoint.

Secrets entered in a Postman environment are local workspace data. Clear current values before exporting or sharing the environment.

## Runner Notes

The API rate limiter allows a burst of 10 and approximately two requests per second. Use at least a 600 ms collection-runner delay. The end-to-end flows cannot run unattended because OTP entry, wallet funding, provider checkout, shipping, and payout can require external actions.

Stable idempotency keys are generated for wallet payment and waybill create/checkout mutations. Reuse the same key after an ambiguous timeout. Do not create a new key merely because a response was delayed. Carrier-pickup settlement is server-initiated and has its own deterministic idempotency key. Before deliberately starting a new order or waybill run, clear that flow's saved IDs and idempotency-key variables so the pre-request scripts generate fresh keys.
