-- Transfers in both directions between two godowns at once, using StockService's lock order (lower godown id first).
\set dir random(0, 1)
\set q random(1, 5)
SELECT _stress_transfer(:dir, :q);
