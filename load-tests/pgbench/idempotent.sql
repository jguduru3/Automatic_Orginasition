-- Many clients submit the same receipt key at once; exactly one payment may exist per key.
\set key random(1, 20)
BEGIN;
SELECT id FROM customers WHERE id = 1 FOR UPDATE;
SELECT count(*) AS seen FROM payments WHERE tenant_id = 1 AND idempotency_key = 'stress-' || :key \gset
\if :seen = 0
INSERT INTO payments (tenant_id, number, customer_id, amount, mode, idempotency_key)
VALUES (1, 5000000 + :key, 1, 100, 'CASH', 'stress-' || :key);
\endif
END;
