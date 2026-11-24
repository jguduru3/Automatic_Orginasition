# Feature verification (re-check, 24 Nov 2026)

Each requirement was traced from the database, through the API, to the screen, then to a test.
Status: **Done** = built end to end with tests.

## Original six features

| Feature | DB | API | Screen | Status |
| --- | --- | --- | --- | --- |
| WhatsApp order capture → sales order | conversations, messages | webhook (HMAC, dedup), inbox, draft, confirm, ignore, reply | WhatsApp orders | **Done** |
| Live inventory and stock reservation | stock (reserved ≤ on hand) | reserve on confirm, consume on dispatch, release on cancel, adjust, add/edit item | Inventory, draft stock pills | **Done** |
| Customer-specific pricing and credit limits | price_tiers, customer_prices, credit_limit | tier + special rates, credit check, owner override | Customers (credit, details, special rates), draft credit box | **Done** |
| Outstanding payments and collections | invoices, payments, allocations | aging, FIFO receipts, idempotency, reminders | Collections | **Done** (see reminder note) |
| Salesperson and delivery tracking | visits, location_pings, vehicles, POD | check-in, live location (30 s throttle), last position, today's route, driver WhatsApp alert | Field & delivery, Dashboard | **Done** |
| Tally integration | tally_outbox, products.tally_name, customers.tally_ledger | Sales, Receipt and Purchase vouchers out; stock items, closing stock and debtor ledgers in | Tally sync | **Done** |

## Gaps found in this re-check and fixed

| Gap | Fix |
| --- | --- |
| API to book an order existed, but no screen for salesperson or phone orders | "New order" form on Sales orders |
| No way to add or edit items or their WhatsApp match patterns | "Add item" / "Edit" on Inventory (owner), pattern validation |
| No screen for special (project) rates; no API to change them | `GET/PUT /api/customers/{id}/prices` + "Special rates" dialog |
| Customer name, town, GSTIN and salesperson could not be edited | `PUT /api/customers/{id}` + "Details" dialog |
| Salesperson check-in API had no screen | "Check in at a shop" on Field (sends location if allowed) + "Visits today" list |
| Staff could not type a free reply in a chat | "Send reply" in the chat box; "Send as customer" only shows when simulation is on |
| Delivery photos, voice notes and photo orders were stored but could not be opened | "Proof photo" / "Play voice note" / "View photo" buttons |
| User management API had no screen | Team page (owner): add person, remove access |
| Dispatch message said the driver was "notified", but nothing is sent | Message now says which vehicle and driver it was loaded on |
| Payment reminders used free text, which Meta blocks outside the 24-hour chat window | Uses an approved template when `WHATSAPP_REMINDER_TEMPLATE` is set |

New code is covered by 6 more frontend tests (31 total, all passing), 1 more backend integration test, and
7 more SQL statements (104 total) PREPAREd against Postgres.

## Previously "not built", now built

| Item | How it works | Tests |
| --- | --- | --- |
| Pull masters and stock from Tally | `POST /api/tally/pull`: Tally XML export of stock items and ledgers; matches by Tally name, then name or phone; optional "set stock to closing balance" in the main godown, never below reserved; new customers start at a zero credit limit | Parser unit tests (incl. XXE blocked), integration test (idempotent second pull), UI test |
| Voice-note transcription | Azure AI Speech (short-audio REST, `hi-IN` default, configurable). Transcript becomes the message text, so the normal parser builds the draft. "Transcribe" button re-runs it | Parsing unit tests; Bicep creates the Speech resource |
| Reading photos of order lists | Azure AI Vision Read (Image Analysis 4.0). Same flow as voice notes | Parsing unit tests; Bicep creates the Vision resource |
| Live GPS trail | Field app shares position every minute while on duty; owner sees who is live, where now, and today's route in Google Maps; pings kept 30 days | Integration test (throttle, validation, roles), UI tests |
| Purchase orders | Suppliers, reorder suggestions (low stock minus what's on order), draft → WhatsApp to supplier → goods receipt (partial or full) → stock in + Purchase voucher to Tally | Integration test (state rules, over-receipt refused, batch required), 2 UI tests, k6 |
| Multiple godowns | Stock per item per godown; orders ship from a chosen godown; transfers keep batch identity; per-godown views | pgbench transfer race (no deadlock, stock conserved), integration + UI tests |
| Driver notification | On dispatch, the driver gets the drop list on WhatsApp (template when configured) and the customer gets "out for delivery" | Integration test (driverAlerted) |
| Signup for new businesses | `POST /api/signup` (off by default; `SIGNUP_ENABLED`) creates tenant, owner, tiers, main godown, counters; rate-limited | Integration test (isolation, duplicate email), UI test |
| Batch and expiry (pharma) | Batch-tracked items reserve first-expiry-first-out and never sell expired stock; dispatch consumes the picked batches; near-expiry report; GRN and adjustments take batch + expiry | pgbench FEFO race (expired never reserved, earliest used first), integration test with a second demo tenant |
| Drug licence check | Items marked "buyer needs licence" can't be sold to a customer whose licence is missing or expired (422 `LICENCE_INVALID`); the draft shows why | Integration + UI tests |

A second demo business, **Sai Medico Distributors** (`owner@pharma.demo.in`), shows batches, two godowns (incl. cold room), expiring stock and licences.

## Still open

| Item | Note |
| --- | --- |
| WhatsApp templates | Reminder, dispatch and PO templates must be created and approved in Meta, then named in the deploy settings. Until then those messages go as plain text, which Meta only delivers inside the 24-hour window |
| Voice and photo accuracy | Depends on Azure AI; mixed Hindi/English and handwriting will sometimes need a person to correct the draft |
| Tally behind office NAT | `TALLY_MODE=http` needs the Tally PC reachable (VPN or a small relay) |
| Map view | Routes open in Google Maps; there is no map drawn inside the app (the page security policy blocks outside map tiles) |

## Still unverified

The Java backend has not been compiled, because Maven Central is blocked in the build workspace. The SQL (179 statements
plus 16 assembled variants), parser, Tally XML and master parsing, concurrency logic (7 pgbench races) and frontend
(40 tests, real-browser render) were all run. The first CI run compiles and runs the 30 backend tests, 21 Playwright
tests and 8 k6 load tests.
