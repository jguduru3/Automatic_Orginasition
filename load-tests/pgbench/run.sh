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
  SELECT (SELECT reserved FROM stock s JOIN products p ON p.id = s.product_id WHERE p.sku = 'RC40' AND s.godown_id = (SELECT id FROM godowns WHERE tenant_id = 1 AND is_default)) AS rc40_reserved,
         (SELECT on_hand FROM stock s JOIN products p ON p.id = s.product_id WHERE p.sku = 'RC40' AND s.godown_id = (SELECT id FROM godowns WHERE tenant_id = 1 AND is_default)) AS rc40_on_hand,
         (SELECT sum(paid) FROM invoices) AS paid,
         (SELECT sum(on_hand) FROM stock) AS on_hand;
UPDATE stock SET on_hand = on_hand + 200 WHERE product_id = (SELECT id FROM products WHERE sku = 'RC40' AND tenant_id = 1)
  AND godown_id = (SELECT g.id FROM godowns g JOIN tenants t ON t.id = g.tenant_id WHERE g.is_default AND t.name = 'Shree Datta Electricals');

-- Mirrors StockService.reserve for a batch-tracked item (same statements, same lock order).
CREATE FUNCTION _stress_reserve_fefo(want int) RETURNS int LANGUAGE plpgsql AS $$
DECLARE pid bigint; gid bigint; b record; left_ int := want; q int;
BEGIN
  SELECT p.id, g.id INTO pid, gid FROM products p JOIN godowns g ON g.tenant_id = p.tenant_id AND g.is_default WHERE p.sku = 'PCM650';
  PERFORM 1 FROM stock WHERE product_id = pid AND godown_id = gid FOR UPDATE;
  FOR b IN SELECT id, on_hand - reserved AS free FROM stock_batches
           WHERE product_id = pid AND godown_id = gid AND expiry_date > CURRENT_DATE AND on_hand > reserved
           ORDER BY expiry_date, id FOR UPDATE LOOP
    EXIT WHEN left_ = 0;
    q := least(left_, b.free);
    UPDATE stock_batches SET reserved = reserved + q WHERE id = b.id;
    left_ := left_ - q;
  END LOOP;
  IF want - left_ > 0 THEN
    UPDATE stock SET reserved = reserved + (want - left_) WHERE product_id = pid AND godown_id = gid;
  END IF;
  RETURN want - left_;
END $$;

-- Mirrors ProductController.transfer for a plain item: lock lower godown id first, move only free stock.
CREATE FUNCTION _stress_transfer(dir int, q int) RETURNS void LANGUAGE plpgsql AS $$
DECLARE pid bigint; a bigint; b bigint; src bigint; dst bigint; moved int;
BEGIN
  SELECT p.id INTO pid FROM products p JOIN tenants t ON t.id = p.tenant_id WHERE p.sku = 'W15' AND t.name = 'Shree Datta Electricals';
  SELECT min(g.id), max(g.id) INTO a, b FROM godowns g JOIN tenants t ON t.id = g.tenant_id WHERE t.name = 'Shree Datta Electricals';
  IF dir = 0 THEN src := a; dst := b; ELSE src := b; dst := a; END IF;
  PERFORM 1 FROM stock WHERE product_id = pid AND godown_id = least(src, dst) FOR UPDATE;
  PERFORM 1 FROM stock WHERE product_id = pid AND godown_id = greatest(src, dst) FOR UPDATE;
  UPDATE stock SET on_hand = on_hand - q WHERE product_id = pid AND godown_id = src AND on_hand - reserved >= q;
  GET DIAGNOSTICS moved = ROW_COUNT;
  IF moved = 1 THEN UPDATE stock SET on_hand = on_hand + q WHERE product_id = pid AND godown_id = dst; END IF;
END $$;
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
RC40="FROM stock s JOIN products p ON p.id = s.product_id JOIN godowns g ON g.id = s.godown_id AND g.is_default JOIN tenants t ON t.id = p.tenant_id AND t.name = 'Shree Datta Electricals' WHERE p.sku = 'RC40'"
check "never reserves more than on hand" "SELECT reserved <= on_hand $RC40"
check "reserved = opening reserve + every successful take" "SELECT s.reserved = (SELECT rc40_reserved FROM _stress_before) + (SELECT COALESCE(sum(qty),0) FROM _stress_reserve) $RC40"
check "all free stock was sold, none oversold" "SELECT s.on_hand - s.reserved = 0 $RC40"

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

bench "Batch reservation, first expiry first out (pharma)" fefo.sql
check "expired batch never reserved" "SELECT COALESCE(bool_and(reserved = 0), true) FROM stock_batches WHERE expiry_date <= CURRENT_DATE"
check "batch reserved totals equal stock reserved" "SELECT bool_and(s.reserved = (SELECT COALESCE(sum(b.reserved),0) FROM stock_batches b WHERE b.product_id = s.product_id AND b.godown_id = s.godown_id)) FROM stock s JOIN products p ON p.id = s.product_id WHERE p.batch_tracked"
check "earliest batches used first" "SELECT NOT EXISTS (SELECT 1 FROM stock_batches a JOIN stock_batches l ON l.product_id = a.product_id AND l.godown_id = a.godown_id AND l.expiry_date > a.expiry_date WHERE a.expiry_date > CURRENT_DATE AND a.on_hand > a.reserved AND l.reserved > 0)"
check "no batch over-reserved" "SELECT bool_and(reserved <= on_hand) FROM stock_batches"

psql -q -c "CREATE TABLE _stress_before2 AS SELECT (SELECT sum(on_hand) FROM stock s JOIN products p ON p.id = s.product_id WHERE p.sku = 'W15' AND p.tenant_id = 1) AS w15_total"
bench "Transfers between godowns, both directions" transfer.sql
check "stock conserved across godowns" "SELECT (SELECT sum(on_hand) FROM stock s JOIN products p ON p.id = s.product_id WHERE p.sku = 'W15' AND p.tenant_id = 1) = w15_total FROM _stress_before2"
check "no godown below reserved" "SELECT bool_and(on_hand >= reserved) FROM stock"

psql -d postgres -q -c "DROP DATABASE $DB"
if [ $fail -ne 0 ]; then echo "STRESS CHECKS FAILED"; exit 1; fi
echo "All stress checks passed"
