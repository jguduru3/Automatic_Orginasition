-- Reserve then either dispatch (consume) or cancel (release) in the same transaction pattern as OrderService.
\set p random(1, 15)
\set q random(1, 4)
\set action random(0, 1)
BEGIN;
SELECT on_hand - reserved AS free FROM stock WHERE product_id = :p FOR UPDATE \gset
\set take least(:q, greatest(:free, 0))
\if :take > 0
UPDATE stock SET reserved = reserved + :take WHERE product_id = :p;
\if :action = 1
UPDATE stock SET on_hand = on_hand - :take, reserved = reserved - :take WHERE product_id = :p AND reserved >= :take AND on_hand >= :take;
\else
UPDATE stock SET reserved = reserved - :take WHERE product_id = :p AND reserved >= :take;
\endif
\endif
END;
