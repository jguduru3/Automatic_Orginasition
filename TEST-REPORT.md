# Test report (24 Nov 2026)

The build workspace could reach npm, GitHub releases and a local Postgres 16, but its network policy blocked Maven
Central. So the Spring Boot code could not be compiled or started there. Everything that did not need Spring was
run. Everything else is written and wired into CI (`.github/workflows/ci.yml`) to run on the first push.

## Run here, all passing

| Area | What ran | Result |
| --- | --- | --- |
| Database schema + demo seed | Flyway SQL applied to Postgres 16 (schema, tenant, 200 historic orders) | Pass |
| Every SQL statement in the backend | `tools/check_sql.py`: 179 statements PREPAREd; `tools/check_dynamic_sql.py`: 16 assembled variants (godown filters, shared stock fragment) | 195/195 pass |
| WhatsApp order parser | Compiled with `javac`, 10 real retailer messages incl. Hindi and voice-note text, 5,000 fuzz inputs | Pass, after fixing a bug (below) |
| Tally XML | Compiled, parsed as XML, debit/credit totals balance, escaping of `& < > ' "` and control chars | Pass |
| Stock reservation race | pgbench, 50 clients × 200 orders on the same scarce SKU | No oversell; reserved = sum of takes |
| Document numbers | pgbench, 10,000 concurrent numbers | No duplicates, no gaps |
| Payment allocation | pgbench, 10,000 concurrent receipts, oldest bill first | No overpaid invoice; allocations add up |
| Idempotent receipts | pgbench, 10,000 submits over 20 keys | Exactly one payment per key |
| Reserve → dispatch/cancel | pgbench, 10,000 mixed transactions over all SKUs | Stock never negative, reserved ≤ on hand |
| Negative control | Same race with the row lock removed | Test catches it: 111 reserved against 22 on hand |
| Batch reservation (FEFO) | pgbench, 10,000 concurrent pharma reservations | Expired batch never reserved; earliest batches used first; batch totals = stock totals |
| Godown transfers | pgbench, 10,000 transfers in both directions at once | No deadlocks, stock conserved, never below reserved |
| Migrations | Fresh install (V1, V2, 3 demo seeds) and upgrade of an existing V1 database to V2 | Both pass |
| Tally masters + purchase voucher | Compiled; mock export parsed; XXE attack rejected; control characters tolerated; purchase voucher balances | Pass |
| Frontend logic + every page | Vitest: 40 tests (API errors, 401 logout, network failure, draft maths, each module's key action, roles, double-tap payment) | 40/40 pass |
| Real browser render | Chromium, desktop 1400 px and Pixel 7, all 10 modules | No JS errors, no sideways overflow |
| Infra | `bicep build` + `bicep lint` | Clean |
| Load scripts | `k6 inspect` on all 7 scripts | Valid |
| E2E suite | Playwright `tsc` + `--list` (15 tests) | Compiles |

## Bugs found and fixed while testing

1. Parser took a spec number as the quantity: "changeover switch 63A x 2" became qty 63. Quantity now prefers
   explicit markers (`x 2`, `- 12`, `10 box`) and ignores spec numbers (32A, 9W, 20mm, 8 way).
2. Webhook signature was checked on a re-encoded string, which fails for Hindi text when Meta omits the charset.
   It is now checked on the raw bytes.
3. The post-commit WhatsApp sender wrote to the database on an already-committed connection (the write would be
   lost). It now runs in its own transaction.
4. The scheduled Tally sync called its own `@Transactional` method, so `SKIP LOCKED` had no transaction and two
   syncs could post the same voucher. It now runs inside a `TransactionTemplate`.
5. nginx dropped security headers on `/` and `/assets/` (location-level `add_header` overrides server-level).
   Headers now come from one include used in every location.
6. Chat thread crashed in browsers without `Element.scrollTo`. Replaced with `scrollTop`.
7. Load test reused an idempotency key across different customers. The key now includes the customer.
8. Demo data numbering (V1000) would have blocked future migrations (V2…). Flyway out-of-order is enabled.

## Runs in CI on first push (not run here)

| Suite | Covers |
| --- | --- |
| `mvn verify`: 30 JUnit tests | Parser, Tally XML, webhook HMAC; full flow WhatsApp → order → pack → dispatch → photo POD → payment → Tally; credit hold and owner-only override; back-order and cancel; validation, auth and role checks; 40-thread oversell test; 20-thread duplicate payment; 10-thread double confirm; signed webhook with retries |
| Playwright (21 tests) | The whole demo story in a browser against the real stack, plus every module on desktop and phone |
| k6 (8 scripts) | Read endpoints at 60 users (p95 < 800 ms); order storm at 40 users on scarce SKUs; racing pack/dispatch/deliver; payment double submits; auth abuse and malformed input (no 5xx allowed); webhook flood at 80/s with retries and bad signatures; concurrent Tally syncs (each voucher once); transfers, FEFO orders, goods receipts and GPS pings all at once |
| pgbench + SQL check | Repeated on a clean Postgres service in CI |

**Before going live:** the first CI run is the first time the Java code compiles. Expect to fix small compile or
wiring issues there. Treat a green CI run as the gate before the deploy workflow runs.

See `VERIFICATION.md` for the feature-by-feature check.
