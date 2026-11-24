package com.distributoros.purchases;

import com.distributoros.common.ApiException;
import com.distributoros.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT')")
public class PurchaseController {
    private static final Set<String> STATUSES = Set.of("DRAFT", "SENT", "PARTIAL", "RECEIVED", "CANCELLED");
    private final JdbcClient db;
    private final PurchaseService purchases;

    public PurchaseController(JdbcClient db, PurchaseService purchases) { this.db = db; this.purchases = purchases; }

    // ---------- suppliers ----------
    @GetMapping("/suppliers")
    public List<Map<String, Object>> suppliers(@AuthenticationPrincipal AuthUser u) {
        return db.sql("""
                SELECT s.id, s.name, s.phone, s.email, s.gstin, s.lead_days AS "leadDays",
                  (SELECT count(*) FROM purchase_orders po WHERE po.supplier_id = s.id AND po.status IN ('SENT','PARTIAL')) AS "openOrders"
                FROM suppliers s WHERE s.tenant_id = :t AND s.active ORDER BY s.name
                """).param("t", u.tenantId()).query().listOfRows();
    }

    public record SupplierInput(@NotBlank @Size(max = 200) String name, @Pattern(regexp = "\\d{10,15}") String phone,
                                @Email @Size(max = 200) String email, @Size(max = 15) String gstin,
                                @Min(0) @Max(365) Integer leadDays) {}

    @PostMapping("/suppliers")
    public Map<String, Object> addSupplier(@AuthenticationPrincipal AuthUser u, @Valid @RequestBody SupplierInput in) {
        Long id = db.sql("""
                INSERT INTO suppliers (tenant_id, name, phone, email, gstin, lead_days) VALUES (:t, :n, :p, :e, :g, :l) RETURNING id
                """).param("t", u.tenantId()).param("n", in.name().trim()).param("p", in.phone()).param("e", in.email())
                .param("g", in.gstin()).param("l", in.leadDays() == null ? 3 : in.leadDays()).query(Long.class).single();
        return Map.of("id", id);
    }

    @PutMapping("/suppliers/{id}")
    public Map<String, Object> updateSupplier(@AuthenticationPrincipal AuthUser u, @PathVariable long id, @Valid @RequestBody SupplierInput in) {
        int n = db.sql("UPDATE suppliers SET name = :n, phone = :p, email = :e, gstin = :g, lead_days = :l WHERE id = :id AND tenant_id = :t")
                .param("n", in.name().trim()).param("p", in.phone()).param("e", in.email()).param("g", in.gstin())
                .param("l", in.leadDays() == null ? 3 : in.leadDays()).param("id", id).param("t", u.tenantId()).update();
        if (n == 0) throw ApiException.notFound("Supplier");
        return Map.of("ok", true);
    }

    // ---------- purchase orders ----------
    @GetMapping("/purchases")
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthUser u, @RequestParam(defaultValue = "OPEN") String status) {
        String st = status.toUpperCase(Locale.ROOT);
        String filter;
        if ("OPEN".equals(st)) filter = " AND po.status IN ('DRAFT','SENT','PARTIAL')";
        else if ("ALL".equals(st)) filter = "";
        else if (STATUSES.contains(st)) filter = " AND po.status = :st";
        else throw ApiException.badRequest("BAD_STATUS", "Unknown status " + status);
        var q = db.sql("""
                SELECT po.id, po.number, po.status, po.total, po.expected_date AS "expectedDate", po.created_at AS "createdAt",
                       po.sent_at AS "sentAt", s.name AS supplier, g.name AS godown,
                       (po.status IN ('SENT','PARTIAL') AND po.expected_date < CURRENT_DATE) AS late,
                       (SELECT string_agg(l.qty || 'x ' || p.sku, ', ' ORDER BY l.id) FROM po_lines l JOIN products p ON p.id = l.product_id
                         WHERE l.po_id = po.id) AS summary
                FROM purchase_orders po JOIN suppliers s ON s.id = po.supplier_id JOIN godowns g ON g.id = po.godown_id
                WHERE po.tenant_id = :t""" + filter + " ORDER BY po.number DESC LIMIT 200").param("t", u.tenantId());
        if (filter.contains(":st")) q = q.param("st", st);
        return q.query().listOfRows();
    }

    @GetMapping("/purchases/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal AuthUser u, @PathVariable long id) {
        var rows = db.sql("""
                SELECT po.id, po.number, po.status, po.subtotal, po.tax, po.total, po.expected_date AS "expectedDate",
                       s.name AS supplier, g.name AS godown
                FROM purchase_orders po JOIN suppliers s ON s.id = po.supplier_id JOIN godowns g ON g.id = po.godown_id
                WHERE po.id = :id AND po.tenant_id = :t
                """).param("id", id).param("t", u.tenantId()).query().listOfRows();
        if (rows.isEmpty()) throw ApiException.notFound("Purchase order");
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        out.put("lines", db.sql("""
                SELECT l.id, l.product_id AS "productId", p.sku, p.name, p.batch_tracked AS "batchTracked", l.qty,
                       l.received_qty AS "receivedQty", l.rate, l.gst_rate AS "gstRate"
                FROM po_lines l JOIN products p ON p.id = l.product_id WHERE l.po_id = :po ORDER BY l.id
                """).param("po", id).query().listOfRows());
        out.put("receipts", db.sql("""
                SELECT 'GRN-' || number AS grn, supplier_invoice_no AS "supplierInvoiceNo", amount, received_at AS "receivedAt"
                FROM goods_receipts WHERE po_id = :po ORDER BY id
                """).param("po", id).query().listOfRows());
        return out;
    }

    /** Low-stock items with a suggested quantity (up to twice the reorder level) and their last supplier and rate. */
    @GetMapping("/purchases/suggestions")
    public List<Map<String, Object>> suggestions(@AuthenticationPrincipal AuthUser u) {
        return db.sql("""
                SELECT x.*, GREATEST(x."reorderLevel" * 2 - x.free - x."onOrder", 0) AS "suggestedQty" FROM (
                  SELECT p.id AS "productId", p.sku, p.name, p.reorder_level AS "reorderLevel", p.list_rate AS "listRate",
                    CASE WHEN p.batch_tracked THEN COALESCE((SELECT sum(b.on_hand - b.reserved) FROM stock_batches b
                           WHERE b.product_id = p.id AND b.expiry_date > CURRENT_DATE), 0)
                         ELSE COALESCE((SELECT sum(s.on_hand - s.reserved) FROM stock s WHERE s.product_id = p.id), 0) END AS free,
                    COALESCE((SELECT sum(l.qty - l.received_qty) FROM po_lines l JOIN purchase_orders po ON po.id = l.po_id
                              WHERE l.product_id = p.id AND po.status IN ('DRAFT','SENT','PARTIAL')), 0) AS "onOrder",
                    (SELECT po.supplier_id FROM po_lines l JOIN purchase_orders po ON po.id = l.po_id
                      WHERE l.product_id = p.id ORDER BY po.created_at DESC LIMIT 1) AS "lastSupplierId",
                    (SELECT l.rate FROM po_lines l JOIN purchase_orders po ON po.id = l.po_id
                      WHERE l.product_id = p.id ORDER BY po.created_at DESC LIMIT 1) AS "lastRate"
                  FROM products p WHERE p.tenant_id = :t AND p.active) x
                WHERE x.free + x."onOrder" < x."reorderLevel"
                ORDER BY x.sku
                """).param("t", u.tenantId()).query().listOfRows();
    }

    public record LineIn(@NotNull Long productId, @NotNull @Min(1) @Max(1_000_000) Integer qty,
                         @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal rate) {}
    public record NewPo(@NotNull Long supplierId, Long godownId, @NotEmpty @Size(max = 100) List<@Valid LineIn> lines,
                        LocalDate expectedDate, @Size(max = 500) String note) {}

    @PostMapping("/purchases")
    public Map<String, Object> create(@AuthenticationPrincipal AuthUser u, @Valid @RequestBody NewPo in) {
        return purchases.create(u, in.supplierId(), in.godownId(),
                in.lines().stream().map(l -> new PurchaseService.Line(l.productId(), l.qty(), l.rate())).toList(),
                in.expectedDate(), in.note());
    }

    @PostMapping("/purchases/{id}/send")
    public Map<String, Object> send(@AuthenticationPrincipal AuthUser u, @PathVariable long id) { return purchases.send(u, id); }

    public record ReceiptIn(@NotNull Long poLineId, @NotNull @Min(0) @Max(1_000_000) Integer qty, @Size(max = 40) String batchNo,
                            LocalDate expiryDate, LocalDate mfgDate, @PositiveOrZero BigDecimal mrp) {}
    public record Receive(@Size(max = 60) String supplierInvoiceNo, @NotEmpty @Size(max = 100) List<@Valid ReceiptIn> lines) {}

    @PostMapping("/purchases/{id}/receive")
    public Map<String, Object> receive(@AuthenticationPrincipal AuthUser u, @PathVariable long id, @Valid @RequestBody Receive in) {
        return purchases.receive(u, id, in.supplierInvoiceNo(), in.lines().stream()
                .map(l -> new PurchaseService.Receipt(l.poLineId(), l.qty(), l.batchNo(), l.expiryDate(), l.mfgDate(), l.mrp())).toList());
    }

    @PostMapping("/purchases/{id}/cancel")
    public Map<String, Object> cancel(@AuthenticationPrincipal AuthUser u, @PathVariable long id) {
        purchases.cancel(u, id);
        return Map.of("ok", true);
    }
}
