# DistributorOS

Wholesale distribution operating system: WhatsApp order capture (text, voice notes, photos), live stock reservation
across godowns with batch/expiry, customer pricing, credit limits and drug licences, purchases and goods receipt,
collections, live field and delivery tracking, and two-way Tally integration.

| Part | Stack |
| --- | --- |
| `backend/` | Spring Boot 3.3, Java 21, Spring JDBC, Flyway, Postgres 16, JWT auth with roles |
| `frontend/` | React 18 + TypeScript + Vite, served by nginx, which also proxies `/api` |
| `infra/` | Bicep for Azure Container Apps, PostgreSQL Flexible Server, Blob Storage, ACR, Log Analytics |
| `.github/workflows/` | CI (tests, SQL checks, E2E, load tests) and deploy to Azure (OIDC) |
| `e2e/` | Playwright end-to-end suite (desktop and phone) |
| `load-tests/` | k6 stress test per module, pgbench concurrency checks |

## Run locally

```bash
docker compose up --build
# open http://localhost:8080
```

Demo logins (password `Demo@1234`): `owner@demo.in`, `accounts@demo.in`, `amit@demo.in` (sales), `raju@demo.in` (delivery).
Pharma demo (batches, expiry, licences, cold room): `owner@pharma.demo.in`, `sales@pharma.demo.in`.
The demo tenant and data load only when `FLYWAY_LOCATIONS` includes `classpath:db/demo`.

Without Docker: run Postgres 16 with a `dos` database, then `cd backend && mvn spring-boot:run` and
`cd frontend && npm install && npm run dev` (Vite proxies `/api` to port 8080).

## Roles

| Role | Can do |
| --- | --- |
| OWNER | Everything, including credit overrides, products and users |
| ACCOUNTANT | Orders, dispatch, payments, customers' credit, stock adjustments, Tally |
| SALES | WhatsApp inbox (own customers), create orders, payments, reminders, visits |
| DELIVERY | Pack, dispatch, deliver with photo proof |

## How the core rules work

- **Stock reservation:** confirming an order locks the customer row, then each stock row in product-id order, and
  reserves only what is free. Any shortfall becomes back-order. Dispatch consumes reserved stock; cancel releases it.
- **Credit:** exposure = unpaid invoices + open orders. An order that takes exposure over the limit returns
  `422 CREDIT_HOLD` unless the owner sends `overrideCredit: true` (logged).
- **Payments:** clear the oldest bills first. `idempotencyKey` makes double taps and retries count once.
- **Godowns and batches:** stock is held per item per godown. Batch-tracked items reserve first-expiry-first-out and
  never sell expired stock. Items marked as needing a licence can't be sold to customers with a missing or expired
  drug licence.
- **Purchases:** reorder suggestions → purchase order → WhatsApp to supplier → goods receipt (with batch and expiry) →
  stock in and a Purchase voucher for Tally.
- **Tally:** invoices and receipts are written to `tally_outbox` in the same transaction as the business change and
  posted by `POST /api/tally/sync` or the scheduled worker (`TALLY_AUTO_SYNC=true`), using `FOR UPDATE SKIP LOCKED`.
- **WhatsApp:** `POST /api/webhooks/whatsapp` checks Meta's `X-Hub-Signature-256` HMAC, de-duplicates by message id,
  and stores voice notes and photos in Blob Storage. Replies are sent only after the order transaction commits.

## Configuration

| Variable | Default | Notes |
| --- | --- | --- |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | local Postgres | Azure adds `?sslmode=require` |
| `JWT_SECRET` | dev value | 32+ characters in production |
| `WHATSAPP_MODE` | `mock` | `cloud` needs `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_APP_SECRET`, `WHATSAPP_VERIFY_TOKEN` |
| `TALLY_MODE` | `mock` | `http` posts XML to `TALLY_URL` (TallyPrime port 9000 through a relay or VPN) |
| `BLOB_MODE` | `local` | `azure` uses `AZURE_STORAGE_CONNECTION_STRING` or `AZURE_STORAGE_ACCOUNT_URL` + managed identity |
| `SIMULATE_ENABLED` | `true` | Turn off in production; it lets users inject test WhatsApp messages |
| `LOGIN_RATE_LIMIT` | `10` | Attempts per IP and email per 5 minutes |
| `SIGNUP_ENABLED` | `false` | Lets new businesses create an account |
| `AZURE_SPEECH_KEY`, `AZURE_SPEECH_REGION`, `AZURE_SPEECH_LANGUAGE` | off | Voice-note transcription (Bicep creates the resource) |
| `AZURE_VISION_ENDPOINT`, `AZURE_VISION_KEY` | off | Reading photos of handwritten order lists |
| `WHATSAPP_REMINDER_TEMPLATE`, `WHATSAPP_DISPATCH_TEMPLATE`, `WHATSAPP_PO_TEMPLATE` | plain text | Approved Meta templates for messages outside the 24-hour window |
| `TALLY_PURCHASE_LEDGER`, `TALLY_INPUT_CGST_LEDGER`, `TALLY_INPUT_SGST_LEDGER`, `TALLY_DEBTORS_GROUP` | see application.yml | Tally ledger names |

## Deploy to Azure

1. Create an Entra app registration with a federated credential for this GitHub repo (environment `production`),
   and give it Contributor and User Access Administrator on the target resource group.
2. Repository variables: `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `AZURE_RG`, `AZURE_LOCATION`
   (e.g. `centralindia`), `APP_PREFIX` (e.g. `dosprod`).
3. Repository secrets: `POSTGRES_ADMIN_PASSWORD`, `JWT_SECRET`, and the three `WHATSAPP_*` values when going live.
4. Push to `main`. When CI passes, **Deploy to Azure** creates the platform, builds both images in ACR, deploys the
   two container apps and smoke-tests the URL. Run it manually to load demo data or switch WhatsApp and Tally to live.
5. In Meta's WhatsApp settings, set the webhook to the `whatsappWebhookUrl` shown in the deploy summary and set the
   tenant's `wa_phone_number_id` in the `tenants` table.

The API has internal ingress only; the public web app proxies `/api`. Blob Storage has shared keys disabled and is
reached through the app's managed identity.

## Tests

```bash
cd backend && mvn verify            # unit + Testcontainers integration + concurrency tests (needs Docker)
cd frontend && npm test             # 40 component and logic tests
bash load-tests/pgbench/run.sh      # DB concurrency invariants (needs a migrated + seeded "dos" database)
python3 tools/check_sql.py backend/src/main/java postgresql://...           # PREPAREs every SQL statement
python3 tools/check_dynamic_sql.py backend/src/main/java postgresql://...   # and every assembled variant
docker compose up -d --wait && (cd e2e && npm ci && npx playwright test) && for s in load-tests/k6/*.js; do k6 run $s; done
```

See `TEST-REPORT.md` for what has been run and what runs in CI.
