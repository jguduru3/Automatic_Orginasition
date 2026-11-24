# Feature verification (re-check, 24 Nov 2026)

Each requirement was traced from the database, through the API, to the screen, then to a test.
Status: **Done** = built end to end with tests. **Partial** = works, with the limit noted. **Not built** = out of scope so far.

## Original six features

| Feature | DB | API | Screen | Status |
| --- | --- | --- | --- | --- |
| WhatsApp order capture → sales order | conversations, messages | webhook (HMAC, dedup), inbox, draft, confirm, ignore, reply | WhatsApp orders | **Done** |
| Live inventory and stock reservation | stock (reserved ≤ on hand) | reserve on confirm, consume on dispatch, release on cancel, adjust, add/edit item | Inventory, draft stock pills | **Done** |
| Customer-specific pricing and credit limits | price_tiers, customer_prices, credit_limit | tier + special rates, credit check, owner override | Customers (credit, details, special rates), draft credit box | **Done** |
| Outstanding payments and collections | invoices, payments, allocations | aging, FIFO receipts, idempotency, reminders | Collections | **Done** (see reminder note) |
| Salesperson and delivery tracking | visits, vehicles, order timestamps, POD | check-in with location, visits today, MTD vs target, dispatch/deliver with photo | Field & delivery, Dashboard | **Partial**: check-ins and delivery status, no live GPS trail |
| Tally integration | tally_outbox | Sales and Receipt vouchers, manual + scheduled sync, retry | Tally sync | **Partial**: push only |

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

## Not built yet (decide whether you need them)

| Item | Why it matters |
| --- | --- |
| Pull masters and stock **from** Tally | Today the app's item list and stock are its own; Tally only receives vouchers |
| Voice-note transcription | Voice notes are stored and playable, but a person must type the order |
| Reading handwritten order photos | Same as above for photos |
| Live GPS trail for vans and salespeople | Only check-in points and delivery status today |
| Purchase orders to suppliers | Low-stock list exists; no PO workflow |
| Multiple godowns | One stock location per business |
| Driver notification | The app does not message the driver on dispatch |
| Signup for new businesses | Tenants are created in the database; there is no self-serve signup |
| Batch and expiry (pharma) | Needed before selling to pharma distributors |

## Still unverified

The Java backend has not been compiled, because Maven Central is blocked in the build workspace. The SQL, parser,
Tally XML, concurrency logic and frontend were all run. The first CI run compiles and runs the 20 backend tests, the
Playwright suite and the k6 load tests.
