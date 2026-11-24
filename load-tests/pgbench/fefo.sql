-- Batch reservation exactly as StockService.reserve: lock the product/godown stock row, then take free, unexpired
-- batches in expiry order. Paracetamol at Sai Medico has one expired batch that must never be touched.
\set want random(1, 25)
SELECT _stress_reserve_fefo(:want);
