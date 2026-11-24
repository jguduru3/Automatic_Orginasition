-- Same statements as OrderService.create: lock customer, lock stock row, reserve what is free.
\set cust random(1, 8)
\set want random(1, 3)
BEGIN;
SELECT id FROM customers WHERE id = :cust FOR UPDATE;
SELECT on_hand - reserved AS free FROM stock WHERE product_id = (SELECT id FROM products WHERE sku = 'RC40' AND tenant_id = 1) AND godown_id = (SELECT id FROM godowns WHERE tenant_id = 1 AND is_default) FOR UPDATE \gset
\set take least(:want, greatest(:free, 0))
\if :take > 0
UPDATE stock SET reserved = reserved + :take WHERE product_id = (SELECT id FROM products WHERE sku = 'RC40' AND tenant_id = 1) AND godown_id = (SELECT id FROM godowns WHERE tenant_id = 1 AND is_default);
INSERT INTO _stress_reserve (qty) VALUES (:take);
\endif
END;
