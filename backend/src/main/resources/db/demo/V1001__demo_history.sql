-- 30 working days of delivered, invoiced and paid orders so the dashboard has history. Deterministic.
SELECT setseed(0.42);

CREATE TEMP TABLE _hist AS
SELECT d::date AS day, gs AS k, c.id AS customer_id, c.tenant_id, c.salesperson_id,
       (ARRAY['WHATSAPP','WHATSAPP','WHATSAPP','SALESPERSON','SALESPERSON','PHONE'])[1 + floor(random() * 6)::int] AS source
FROM generate_series(CURRENT_DATE - 30, CURRENT_DATE - 1, interval '1 day') d
CROSS JOIN generate_series(1, 8) gs
CROSS JOIN LATERAL (SELECT id, tenant_id, salesperson_id FROM customers ORDER BY random() + gs * 0 + extract(day FROM d) * 0 LIMIT 1) c
WHERE extract(isodow FROM d) <> 7;

ALTER TABLE _hist ADD COLUMN order_id BIGINT, ADD COLUMN number BIGINT;
UPDATE _hist h SET number = 900000 + x.rn FROM (SELECT day, k, row_number() OVER (ORDER BY day, k) rn FROM _hist) x
WHERE x.day = h.day AND x.k = h.k;

INSERT INTO sales_orders (tenant_id, number, customer_id, source, status, salesperson_id, subtotal, tax, total,
                          created_at, packed_at, dispatched_at, delivered_at, godown_id)
SELECT tenant_id, number, customer_id, source, 'DELIVERED', salesperson_id, 0, 0, 0,
       day + time '10:00' + (k * interval '35 minutes'), day + time '13:00', day + time '15:00', day + time '18:00',
       (SELECT id FROM godowns g WHERE g.tenant_id = _hist.tenant_id AND g.is_default)
FROM _hist;

UPDATE _hist h SET order_id = o.id FROM sales_orders o WHERE o.number = h.number AND o.tenant_id = h.tenant_id;

INSERT INTO order_lines (order_id, product_id, qty, rate, gst_rate, amount)
SELECT h.order_id, p.id, q.qty, p.list_rate, p.gst_rate, p.list_rate * q.qty
FROM _hist h
CROSS JOIN LATERAL (SELECT id, list_rate, gst_rate FROM products WHERE tenant_id = h.tenant_id
                    ORDER BY random() + h.k * 0 LIMIT 3) p
CROSS JOIN LATERAL (SELECT 2 + floor(random() * 18)::int + h.k * 0 AS qty) q;

UPDATE sales_orders so SET subtotal = t.sub, tax = t.tax, total = t.sub + t.tax
FROM (SELECT order_id, sum(amount) sub, round(sum(amount * gst_rate / 100), 2) tax FROM order_lines GROUP BY order_id) t
WHERE t.order_id = so.id AND so.number >= 900000;

-- historic invoices fully paid (opening receivables are seeded separately)
INSERT INTO invoices (tenant_id, number, customer_id, order_id, invoice_date, amount, paid)
SELECT o.tenant_id, o.number, o.customer_id, o.id, o.created_at::date, o.total, o.total
FROM sales_orders o WHERE o.number >= 900000;

INSERT INTO payments (tenant_id, number, customer_id, amount, mode, received_at)
SELECT o.tenant_id, o.number, o.customer_id, o.total,
       (ARRAY['UPI','NEFT','CHEQUE','CASH'])[1 + (o.number % 4)::int], o.delivered_at + interval '1 day'
FROM sales_orders o WHERE o.number >= 900000 AND o.delivered_at + interval '1 day' < now();

INSERT INTO visits (tenant_id, user_id, customer_id, checked_in_at)
SELECT c.tenant_id, c.salesperson_id, c.id, date_trunc('day', now()) + time '09:30' + (row_number() OVER () * interval '25 minutes')
FROM customers c WHERE c.salesperson_id IS NOT NULL;

DROP TABLE _hist;
