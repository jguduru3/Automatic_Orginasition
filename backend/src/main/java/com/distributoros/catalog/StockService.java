package com.distributoros.catalog;

import com.distributoros.common.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * All stock movements go through here. One row in {@code stock} per (product, godown) holds the totals and is the lock
 * that serialises every movement of that product in that godown. Batch-tracked products also keep one row per batch in
 * {@code stock_batches}; reservations pick batches first-expiry-first-out and never touch expired batches.
 * Callers must already be inside a transaction.
 */
@Service
public class StockService {
    private final JdbcClient db;

    public StockService(JdbcClient db) { this.db = db; }

    public record BatchTake(long batchId, int qty) {}
    public record Reservation(int taken, List<BatchTake> batches) {}

    /** SQL fragment: sellable free quantity of product p in godown g (expired batches excluded). */
    public static final String FREE_SQL = """
            CASE WHEN p.batch_tracked THEN COALESCE((SELECT sum(b.on_hand - b.reserved) FROM stock_batches b
                   WHERE b.product_id = p.id AND b.godown_id = %s AND b.expiry_date > CURRENT_DATE), 0)
                 ELSE COALESCE((SELECT s.on_hand - s.reserved FROM stock s WHERE s.product_id = p.id AND s.godown_id = %s), 0) END
            """;

    public static String freeIn(String godownExpr) { return FREE_SQL.formatted(godownExpr, godownExpr); }

    public long defaultGodown(long tenantId) {
        return db.sql("SELECT id FROM godowns WHERE tenant_id = :t AND is_default").param("t", tenantId)
                .query(Long.class).optional().orElseThrow(() -> ApiException.conflict("NO_GODOWN", "No default godown configured"));
    }

    /** Validates a caller-supplied godown, or returns the default one. */
    public long godown(long tenantId, Long godownId) {
        if (godownId == null) return defaultGodown(tenantId);
        return db.sql("SELECT id FROM godowns WHERE id = :g AND tenant_id = :t AND active").param("g", godownId).param("t", tenantId)
                .query(Long.class).optional().orElseThrow(() -> ApiException.badRequest("BAD_GODOWN", "Godown not found"));
    }

    private boolean batchTracked(long productId) {
        return db.sql("SELECT batch_tracked FROM products WHERE id = :p").param("p", productId).query(Boolean.class).single();
    }

    /** Locks (creating if needed) the stock row for product+godown. */
    private void lockRow(long tenantId, long productId, long godownId) {
        db.sql("""
                INSERT INTO stock (product_id, tenant_id, godown_id, on_hand) VALUES (:p, :t, :g, 0)
                ON CONFLICT (product_id, godown_id) DO NOTHING
                """).param("p", productId).param("t", tenantId).param("g", godownId).update();
        db.sql("SELECT 1 FROM stock WHERE product_id = :p AND godown_id = :g FOR UPDATE")
                .param("p", productId).param("g", godownId).query(Integer.class).single();
    }

    /** Reserves up to {@code want} units. Returns what was actually reserved (rest is back-order). */
    public Reservation reserve(long tenantId, long productId, long godownId, int want) {
        lockRow(tenantId, productId, godownId);
        List<BatchTake> takes = new ArrayList<>();
        int taken;
        if (batchTracked(productId)) {
            var batches = db.sql("""
                    SELECT id, on_hand - reserved AS free FROM stock_batches
                    WHERE product_id = :p AND godown_id = :g AND expiry_date > CURRENT_DATE AND on_hand > reserved
                    ORDER BY expiry_date, id FOR UPDATE
                    """).param("p", productId).param("g", godownId).query().listOfRows();
            int left = want;
            for (Map<String, Object> b : batches) {
                if (left == 0) break;
                int q = Math.min(left, ((Number) b.get("free")).intValue());
                long id = ((Number) b.get("id")).longValue();
                db.sql("UPDATE stock_batches SET reserved = reserved + :q WHERE id = :id").param("q", q).param("id", id).update();
                takes.add(new BatchTake(id, q));
                left -= q;
            }
            taken = want - left;
        } else {
            int free = db.sql("SELECT on_hand - reserved FROM stock WHERE product_id = :p AND godown_id = :g")
                    .param("p", productId).param("g", godownId).query(Integer.class).single();
            taken = Math.max(0, Math.min(want, free));
        }
        if (taken > 0) {
            db.sql("UPDATE stock SET reserved = reserved + :q, updated_at = now() WHERE product_id = :p AND godown_id = :g")
                    .param("q", taken).param("p", productId).param("g", godownId).update();
        }
        return new Reservation(taken, takes);
    }

    public void recordBatches(long orderLineId, List<BatchTake> takes) {
        for (BatchTake t : takes) {
            db.sql("INSERT INTO order_line_batches (order_line_id, batch_id, qty) VALUES (:l, :b, :q)")
                    .param("l", orderLineId).param("b", t.batchId()).param("q", t.qty()).update();
        }
    }

    /** Dispatch (consume=true) takes reserved stock out of the godown; cancel (consume=false) just releases it. */
    public void settleOrder(long orderId, boolean consume) {
        var lines = db.sql("""
                SELECT l.id, l.product_id, l.qty - l.backorder_qty AS q, o.godown_id, o.tenant_id
                FROM order_lines l JOIN sales_orders o ON o.id = l.order_id
                WHERE l.order_id = :o AND l.qty > l.backorder_qty ORDER BY l.product_id
                """).param("o", orderId).query().listOfRows();
        for (var l : lines) {
            long pid = ((Number) l.get("product_id")).longValue();
            long gid = ((Number) l.get("godown_id")).longValue();
            int q = ((Number) l.get("q")).intValue();
            lockRow(((Number) l.get("tenant_id")).longValue(), pid, gid);
            var allocs = db.sql("SELECT batch_id, qty FROM order_line_batches WHERE order_line_id = :l ORDER BY batch_id")
                    .param("l", ((Number) l.get("id")).longValue()).query().listOfRows();
            for (var a : allocs) {
                int n = db.sql((consume
                        ? "UPDATE stock_batches SET on_hand = on_hand - :q, reserved = reserved - :q WHERE id = :b AND reserved >= :q"
                        : "UPDATE stock_batches SET reserved = reserved - :q WHERE id = :b AND reserved >= :q"))
                        .param("q", ((Number) a.get("qty")).intValue()).param("b", ((Number) a.get("batch_id")).longValue()).update();
                if (n == 0) throw ApiException.conflict("STOCK_MISMATCH", "Batch stock no longer matches this order");
            }
            int n = db.sql((consume
                    ? "UPDATE stock SET on_hand = on_hand - :q, reserved = reserved - :q, updated_at = now() WHERE product_id = :p AND godown_id = :g AND reserved >= :q"
                    : "UPDATE stock SET reserved = reserved - :q, updated_at = now() WHERE product_id = :p AND godown_id = :g AND reserved >= :q"))
                    .param("q", q).param("p", pid).param("g", gid).update();
            if (n == 0) throw ApiException.conflict("STOCK_MISMATCH", "Reserved stock no longer matches this order");
        }
    }

    /** Goods in (GRN, positive adjustment, transfer in). Returns the batch id for batch-tracked products. */
    public Long receive(long tenantId, long productId, long godownId, int qty, String batchNo, LocalDate expiry,
                        LocalDate mfg, BigDecimal mrp) {
        if (qty <= 0) throw ApiException.badRequest("BAD_QTY", "Quantity must be positive");
        lockRow(tenantId, productId, godownId);
        Long batchId = null;
        if (batchTracked(productId)) {
            if (batchNo == null || batchNo.isBlank() || expiry == null) {
                throw ApiException.badRequest("BATCH_REQUIRED", "Batch number and expiry date are required for this item");
            }
            batchId = db.sql("""
                    INSERT INTO stock_batches (tenant_id, product_id, godown_id, batch_no, expiry_date, mfg_date, mrp, on_hand)
                    VALUES (:t, :p, :g, :b, :e, :m, :mrp, :q)
                    ON CONFLICT (product_id, godown_id, batch_no) DO UPDATE SET on_hand = stock_batches.on_hand + EXCLUDED.on_hand
                    RETURNING id
                    """).param("t", tenantId).param("p", productId).param("g", godownId).param("b", batchNo.trim())
                    .param("e", expiry).param("m", mfg).param("mrp", mrp).param("q", qty).query(Long.class).single();
        }
        db.sql("UPDATE stock SET on_hand = on_hand + :q, updated_at = now() WHERE product_id = :p AND godown_id = :g")
                .param("q", qty).param("p", productId).param("g", godownId).update();
        return batchId;
    }

    /** Goods out without an order (damage, transfer out, negative count correction). Cannot touch reserved stock. */
    public void remove(long tenantId, long productId, long godownId, int qty, Long batchId) {
        if (qty <= 0) throw ApiException.badRequest("BAD_QTY", "Quantity must be positive");
        lockRow(tenantId, productId, godownId);
        if (batchTracked(productId)) {
            if (batchId == null) throw ApiException.badRequest("BATCH_REQUIRED", "Choose the batch to take stock from");
            int n = db.sql("""
                    UPDATE stock_batches SET on_hand = on_hand - :q
                    WHERE id = :b AND product_id = :p AND godown_id = :g AND on_hand - reserved >= :q
                    """).param("q", qty).param("b", batchId).param("p", productId).param("g", godownId).update();
            if (n == 0) throw ApiException.conflict("STOCK_BELOW_RESERVED", "That batch does not have this much unreserved stock");
        }
        int n = db.sql("""
                UPDATE stock SET on_hand = on_hand - :q, updated_at = now()
                WHERE product_id = :p AND godown_id = :g AND on_hand - reserved >= :q
                """).param("q", qty).param("p", productId).param("g", godownId).update();
        if (n == 0) throw ApiException.conflict("STOCK_BELOW_RESERVED", "Stock cannot go below what is reserved for open orders");
    }

    public Map<String, Object> batch(long batchId) {
        return db.sql("SELECT batch_no, expiry_date, mfg_date, mrp FROM stock_batches WHERE id = :b").param("b", batchId)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> ApiException.notFound("Batch"));
    }
}
