-- Same pattern as PaymentService.record: lock customer, then oldest open invoice, allocate.
\set cust random(1, 8)
\set amt random(100, 5000)
BEGIN;
SELECT id FROM customers WHERE id = :cust FOR UPDATE;
SELECT COALESCE((SELECT id FROM invoices WHERE customer_id = :cust AND paid < amount ORDER BY invoice_date, id LIMIT 1 FOR UPDATE), 0) AS inv \gset
\if :inv > 0
SELECT (amount - paid)::bigint AS due FROM invoices WHERE id = :inv \gset
\set x least(:amt, :due)
UPDATE invoices SET paid = paid + :x WHERE id = :inv;
INSERT INTO _stress_alloc (invoice_id, amount) VALUES (:inv, :x);
\endif
END;
