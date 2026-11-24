#!/usr/bin/env bash
# Concurrency stress on the database paths where stock and money can go wrong.
# Needs PG* env vars (or local trust auth) and a database already migrated + seeded (db "dos").
set -euo pipefail
cd "$(dirname "$0")"
SRC=${PGDATABASE:-dos}
DB=dos_stress
CLIENTS=${CLIENTS:-50}
TX=${TX:-200}
psql -d postgres -q -c "DROP DATABASE IF EXISTS $DB" -c "CREATE DATABASE $DB TEMPLATE $SRC"
export PGDATABASE=$DB
psql -q -v ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE _stress_reserve (qty INT);
CREATE TABLE _stress_numbers (n BIGINT UNIQUE);
CREATE TABLE _stress_alloc (invoice_id BIGINT, amount NUMERIC);
CREATE TABLE _stress_before AS
  SELECT (SELECT reserved FROM stock s JOIN products p ON p.id = s.product_id WHERE p.sku = 'RC40') AS rc40_reserved,
         (SELECT on_hand FROM stock s JOIN products p ON p.id = s.product_id WHERE p.sku = 'RC40') AS rc40_on_hand,
         (SELECT sum(paid) FROM invoices) AS paid,
         (SELECT sum(on_hand) FROM stock) AS on_hand;
UPDATE stock SET on_hand = on_hand + 200 WHERE product_id = (SELECT id FROM products WHERE sku = 'RC40');
SQL

fail=0
check() { # name, sql returning one boolean
  local ok; ok=$(psql -tA -c "$2")
  if [ "$ok" = "t" ]; then echo "  PASS  $1"; else echo "  FAIL  $1"; fail=1; fi
}
bench() { # name, script
  echo "== $1: $CLIENTS clients x $TX transactions"
  pgbench -n -c "$CLIENTS" -j 4 -t "$TX" -f "$2" 2>&1 | grep -E "number of failed|tps =|latency average" | sed 's/^/  /'
}

bench "Stock reservation race (RCCB, 218 units on hand)" reserve.sql
check "never reserves more than on hand" "SELECT reserved <= on_hand FROM stock s JOIN products p ON p.id = s.product_id WHERE p.sku = 'RC40'"
check "reserved = opening reserve + every successful take" "SELECT s.reserved = b.rc40_reserved + (SELECT COALESCE(sum(qty),0) FROM _stress_reserve) FROM stock s JOIN products p ON p.id = s.product_id, _stress_before b WHERE p.sku = 'RC40'"
check "all free stock was sold, none oversold" "SELECT s.on_hand - s.reserved = 0 FROM stock s JOIN products p ON p.id = s.product_id WHERE p.sku = 'RC40'"

bench "Document numbering" numbering.sql
check "no duplicate numbers" "SELECT count(*) = count(DISTINCT n) FROM _stress_numbers"
check "no gaps" "SELECT max(n) - min(n) + 1 = count(*) FROM _stress_numbers"

bench "Payment allocation (FIFO, oldest bill first)" payments.sql
check "no invoice overpaid" "SELECT bool_and(paid <= amount) FROM invoices"
check "allocations equal the increase in paid" "SELECT (SELECT sum(paid) FROM invoices) - b.paid = (SELECT COALESCE(sum(amount),0) FROM _stress_alloc) FROM _stress_before b"

bench "Idempotent receipts (20 keys, many duplicates)" idempotent.sql
check "exactly one payment per key" "SELECT count(*) = count(DISTINCT idempotency_key) FROM payments WHERE idempotency_key LIKE 'stress-%'"

bench "Reserve then dispatch or cancel, all SKUs" lifecycle.sql
check "stock never negative and reserved within on hand" "SELECT bool_and(on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand) FROM stock"

psql -d postgres -q -c "DROP DATABASE $DB"
if [ $fail -ne 0 ]; then echo "STRESS CHECKS FAILED"; exit 1; fi
echo "All stress checks passed"
