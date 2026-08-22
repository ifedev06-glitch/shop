# Isolated load testing

The `loadtest` Spring profile measures ShopSecure and PostgreSQL without calling external providers.

It uses:

- `StubPaymentProvider` for checkout, virtual accounts, bank resolution, payouts, and withdrawals.
- `StubShippingProvider` instead of Terminal Africa.
- `StubSmsProvider` instead of Twilio.
- suppressed email delivery instead of Resend.
- the fixed OTP `000000` for all load-test verification flows.
- disabled HTTP rate limiting.
- delayed reconciliation scans so they do not add unrelated work during a normal test run.

The profile contains non-production fixture secrets and local database defaults. Never activate it in production.

## Run with Docker Compose

```bash
docker compose -f compose.loadtest.yml up --build
```

The backend is available at `http://localhost:8080`. Provider isolation is enforced by the `loadtest` profile: the Paystack bean is not loaded, Terminal and Twilio are disabled, and email delivery returns before creating a Resend client.

Remove all generated load-test data with:

```bash
docker compose -f compose.loadtest.yml down -v
```

## Run the packaged application

Create the local PostgreSQL database and then run:

```bash
SPRING_PROFILES_ACTIVE=loadtest java -jar target/shopsecure-0.0.1-SNAPSHOT.jar
```

The default connection is:

```text
jdbc:postgresql://localhost:5432/shopsecure_loadtest
username: shopsecure_loadtest
password: shopsecure_loadtest
```

For a remote isolated PostgreSQL instance, override only the load-test database settings:

```bash
LOADTEST_DB_URL=jdbc:postgresql://database-host:5432/shopsecure_loadtest \
LOADTEST_DB_USERNAME=shopsecure_loadtest \
LOADTEST_DB_PASSWORD=shopsecure_loadtest \
SPRING_PROFILES_ACTIVE=loadtest \
java -jar target/shopsecure-0.0.1-SNAPSHOT.jar
```

Checkout initialization is deterministic. Calling the checkout endpoint again immediately verifies the stub payment as successful because `payment.checkout.recheck-after=PT0S`.

## Run the authenticated-read benchmark

With the Compose stack running, execute k6 in the same Docker network:

```bash
docker run --rm \
  --network shopsecure_loadtest \
  -v "$PWD/loadtest/k6:/scripts:ro" \
  -e BASE_URL=http://backend:8080 \
  -e RATE=25 \
  -e DURATION=60s \
  grafana/k6:latest run --quiet /scripts/authenticated-reads.js
```

Each iteration sends four authenticated requests, so `RATE=25` produces approximately 100 HTTP requests per second. The scenario covers profile, wallet, wallet-transaction, and order-list reads. It creates and verifies one fixture account before measurement using OTP `000000`.
