package com.distributoros.catalog;

import com.distributoros.common.Activity;
import com.distributoros.common.ApiException;
import com.distributoros.common.DocNumbers;
import com.distributoros.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

@RestController
@RequestMapping("/api")
public class ProductController {
    private final JdbcClient db;
    private final Activity activity;
    private final StockService stock;
    private final DocNumbers numbers;

    public ProductController(JdbcClient db, Activity activity, StockService stock, DocNumbers numbers) {
        this.db = db; this.activity = activity; this.stock = stock; this.numbers = numbers;
    }

    /** Stock per item, all godowns combined or one godown. "free" excludes expired batches and reserved stock. */
    @GetMapping("/inventory")
    public List<Map<String, Object>> inventory(@AuthenticationPrincipal AuthUser u, @RequestParam(required = false) Long godownId) {
        String g = godownId == null ? "" : " AND s.godown_id = :g";
        String freeExpr = godownId == null
                ? """
                  CASE WHEN p.batch_tracked THEN COALESCE((SELECT sum(b.on_hand - b.reserved) FROM stock_batches b
                         WHERE b.product_id = p.id AND b.expiry_date > CURRENT_DATE), 0)
                       ELSE COALESCE((SELECT sum(s.on_hand - s.reserved) FROM stock s WHERE s.product_id = p.id), 0) END"""
                : StockService.freeIn(":g");
        var st = db.sql("""
                SELECT p.id, p.sku, p.name, p.unit, p.category, p.list_rate AS "listRate", p.gst_rate AS "gstRate",
                       p.reorder_level AS "reorderLevel", array_to_string(p.match_patterns, E'\\n') AS patterns,
                       p.batch_tracked AS "batchTracked", p.requires_licence AS "requiresLicence", p.hsn,
                       COALESCE((SELECT sum(s.on_hand) FROM stock s WHERE s.product_id = p.id""" + g + """
                       ), 0) AS "onHand",
                       COALESCE((SELECT sum(s.reserved) FROM stock s WHERE s.product_id = p.id""" + g + """
                       ), 0) AS reserved,
                       """ + freeExpr + """
                        AS free,
                       COALESCE((SELECT sum(b.on_hand) FROM stock_batches b WHERE b.product_id = p.id AND b.expiry_date <= CURRENT_DATE"""
                + (godownId == null ? "" : " AND b.godown_id = :g") + """
                       ), 0) AS expired,
                       (SELECT min(b.expiry_date) FROM stock_batches b WHERE b.product_id = p.id AND b.on_hand > 0 AND b.expiry_date > CURRENT_DATE) AS "nextExpiry"
                FROM products p
                WHERE p.tenant_id = :t AND p.active
                ORDER BY p.sku
                """).param("t", u.tenantId());
        if (godownId != null) st = st.param("g", stock.godown(u.tenantId(), godownId));
        return st.query().listOfRows();
    }

    /** One item: stock in each godown and every batch. */
    @GetMapping("/inventory/{productId}")
    public Map<String, Object> item(@AuthenticationPrincipal AuthUser u, @PathVariable long productId) {
        var p = db.sql("SELECT id, sku, name, unit, batch_tracked AS \"batchTracked\" FROM products WHERE id = :p AND tenant_id = :t")
                .param("p", productId).param("t", u.tenantId()).query().listOfRows();
        if (p.isEmpty()) throw ApiException.notFound("Item");
        Map<String, Object> out = new LinkedHashMap<>(p.get(0));
        out.put("godowns", db.sql("""
                SELECT g.id AS "godownId", g.name AS godown, COALESCE(s.on_hand, 0) AS "onHand", COALESCE(s.reserved, 0) AS reserved
                FROM godowns g LEFT JOIN stock s ON s.godown_id = g.id AND s.product_id = :p
                WHERE g.tenant_id = :t AND g.active ORDER BY g.is_default DESC, g.name
                """).param("p", productId).param("t", u.tenantId()).query().listOfRows());
        out.put("batches", db.sql("""
                SELECT b.id, b.batch_no AS "batchNo", b.expiry_date AS "expiryDate", b.mfg_date AS "mfgDate", b.mrp,
                       b.on_hand AS "onHand", b.reserved, g.name AS godown, b.godown_id AS "godownId",
                       (b.expiry_date <= CURRENT_DATE) AS expired, (b.expiry_date - CURRENT_DATE) AS "daysLeft"
                FROM stock_batches b JOIN godowns g ON g.id = b.godown_id
                WHERE b.product_id = :p AND b.tenant_id = :t AND b.on_hand > 0 ORDER BY b.expiry_date, b.id
                """).param("p", productId).param("t", u.tenantId()).query().listOfRows());
        return out;
    }

    /** Batches expiring within N days (and already expired) with on-hand stock and its value at list price. */
    @GetMapping("/inventory/expiring")
    public List<Map<String, Object>> expiring(@AuthenticationPrincipal AuthUser u, @RequestParam(defaultValue = "90") int days) {
        return db.sql("""
                SELECT b.id, p.sku, p.name, b.batch_no AS "batchNo", b.expiry_date AS "expiryDate", g.name AS godown,
                       b.on_hand AS "onHand", b.reserved, (b.expiry_date - CURRENT_DATE) AS "daysLeft",
                       b.on_hand * p.list_rate AS value
                FROM stock_batches b JOIN products p ON p.id = b.product_id JOIN godowns g ON g.id = b.godown_id
                WHERE b.tenant_id = :t AND b.on_hand > 0 AND b.expiry_date <= CURRENT_DATE + CAST(:d AS int)
                ORDER BY b.expiry_date, p.sku
                """).param("t", u.tenantId()).param("d", Math.max(0, Math.min(days, 730))).query().listOfRows();
    }

    public record ProductInput(@NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9_\\-/.]+") String sku,
                               @NotBlank @Size(max = 200) String name,
                               @NotBlank @Size(max = 40) String unit,
                               @NotBlank @Size(max = 60) String category,
                               @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal listRate,
                               @NotNull @DecimalMin("0") @DecimalMax("28") BigDecimal gstRate,
                               @NotNull @PositiveOrZero Integer reorderLevel,
                               @Size(max = 10) List<@Size(max = 200) String> patterns,
                               boolean batchTracked,
                               boolean requiresLicence,
                               @Size(max = 12) String hsn) {}

    @PostMapping("/products")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public Map<String, Object> create(@AuthenticationPrincipal AuthUser u, @Valid @RequestBody ProductInput in) {
        String pats = String.join("\n", validPatterns(in.patterns()));
        Long id = db.sql("""
                INSERT INTO products (tenant_id, sku, name, unit, category, list_rate, gst_rate, reorder_level, match_patterns,
                                      batch_tracked, requires_licence, hsn)
                VALUES (:t, :sku, :n, :unit, :cat, :rate, :gst, :ro, string_to_array(:pats, E'\\n'), :bt, :rl, :hsn) RETURNING id
                """).param("t", u.tenantId()).param("sku", in.sku().toUpperCase()).param("n", in.name().trim())
                .param("unit", in.unit()).param("cat", in.category()).param("rate", in.listRate())
                .param("gst", in.gstRate()).param("ro", in.reorderLevel()).param("pats", pats)
                .param("bt", in.batchTracked()).param("rl", in.requiresLicence()).param("hsn", in.hsn())
                .query(Long.class).single();
        db.sql("INSERT INTO stock (product_id, tenant_id, godown_id, on_hand) VALUES (:p, :t, :g, 0)")
                .param("p", id).param("t", u.tenantId()).param("g", stock.defaultGodown(u.tenantId())).update();
        activity.log(u.tenantId(), u.userId(), "PRODUCT_CREATED", "Item " + in.sku() + " added", "product", id);
        return Map.of("id", id);
    }

    @PutMapping("/products/{id}")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public Map<String, Object> update(@AuthenticationPrincipal AuthUser u, @PathVariable long id,
                                      @Valid @RequestBody ProductInput in) {
        String pats = String.join("\n", validPatterns(in.patterns()));
        var cur = db.sql("SELECT batch_tracked FROM products WHERE id = :id AND tenant_id = :t FOR UPDATE")
                .param("id", id).param("t", u.tenantId()).query(Boolean.class).optional()
                .orElseThrow(() -> ApiException.notFound("Product"));
        if (cur != in.batchTracked()) {
            int onHand = db.sql("SELECT COALESCE(sum(on_hand), 0) FROM stock WHERE product_id = :p").param("p", id).query(Integer.class).single();
            if (onHand > 0) throw ApiException.conflict("HAS_STOCK", "Batch tracking can only be switched while the item has no stock");
        }
        db.sql("""
                UPDATE products SET sku = :sku, name = :n, unit = :unit, category = :cat, list_rate = :rate,
                       gst_rate = :gst, reorder_level = :ro, match_patterns = string_to_array(:pats, E'\\n'),
                       batch_tracked = :bt, requires_licence = :rl, hsn = :hsn
                WHERE id = :id AND tenant_id = :t
                """).param("sku", in.sku().toUpperCase()).param("n", in.name().trim()).param("unit", in.unit())
                .param("cat", in.category()).param("rate", in.listRate()).param("gst", in.gstRate())
                .param("ro", in.reorderLevel()).param("pats", pats).param("bt", in.batchTracked())
                .param("rl", in.requiresLicence()).param("hsn", in.hsn()).param("id", id).param("t", u.tenantId()).update();
        return Map.of("ok", true);
    }

    public record StockAdjust(@NotNull @Min(-1_000_000) @Max(1_000_000) Integer delta,
                              @NotBlank @Size(max = 200) String reason,
                              Long godownId,
                              Long batchId,
                              @Size(max = 40) String batchNo,
                              LocalDate expiryDate) {}

    @PostMapping("/products/{id}/stock")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT')")
    @Transactional
    public Map<String, Object> adjust(@AuthenticationPrincipal AuthUser u, @PathVariable long id,
                                      @Valid @RequestBody StockAdjust in) {
        if (in.delta() == 0) throw ApiException.badRequest("BAD_QTY", "Change must not be zero");
        Integer own = db.sql("SELECT count(*) FROM products WHERE id = :p AND tenant_id = :t").param("p", id)
                .param("t", u.tenantId()).query(Integer.class).single();
        if (own == 0) throw ApiException.notFound("Product");
        long g = stock.godown(u.tenantId(), in.godownId());
        if (in.delta() > 0) {
            String batchNo = in.batchNo();
            LocalDate exp = in.expiryDate();
            if (in.batchId() != null) {
                var b = stock.batch(in.batchId());
                batchNo = (String) b.get("batch_no");
                exp = ((java.sql.Date) b.get("expiry_date")).toLocalDate();
            }
            stock.receive(u.tenantId(), id, g, in.delta(), batchNo, exp, null, null);
        } else {
            stock.remove(u.tenantId(), id, g, -in.delta(), in.batchId());
        }
        int onHand = db.sql("SELECT on_hand FROM stock WHERE product_id = :p AND godown_id = :g")
                .param("p", id).param("g", g).query(Integer.class).single();
        activity.log(u.tenantId(), u.userId(), "STOCK_ADJUSTED",
                "Stock adjusted by " + in.delta() + " (" + in.reason() + ")", "product", id);
        return Map.of("onHand", onHand);
    }

    // ---------- godowns and transfers ----------

    @GetMapping("/godowns")
    public List<Map<String, Object>> godowns(@AuthenticationPrincipal AuthUser u) {
        return db.sql("""
                SELECT g.id, g.code, g.name, g.address, g.is_default AS "isDefault",
                       COALESCE((SELECT sum(s.on_hand * p.list_rate) FROM stock s JOIN products p ON p.id = s.product_id
                                 WHERE s.godown_id = g.id), 0) AS "stockValue"
                FROM godowns g WHERE g.tenant_id = :t AND g.active ORDER BY g.is_default DESC, g.name
                """).param("t", u.tenantId()).query().listOfRows();
    }

    public record GodownInput(@NotBlank @Size(max = 12) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
                              @NotBlank @Size(max = 100) String name, @Size(max = 300) String address) {}

    @PostMapping("/godowns")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> addGodown(@AuthenticationPrincipal AuthUser u, @Valid @RequestBody GodownInput in) {
        Long id = db.sql("INSERT INTO godowns (tenant_id, code, name, address) VALUES (:t, :c, :n, :a) RETURNING id")
                .param("t", u.tenantId()).param("c", in.code().toUpperCase()).param("n", in.name().trim()).param("a", in.address())
                .query(Long.class).single();
        return Map.of("id", id);
    }

    public record TransferLine(@NotNull Long productId, @NotNull @Min(1) @Max(1_000_000) Integer qty, Long batchId) {}
    public record Transfer(@NotNull Long fromGodownId, @NotNull Long toGodownId, @NotEmpty @Size(max = 100) List<@Valid TransferLine> lines,
                           @Size(max = 300) String note) {}

    /** Moves free stock between godowns; batch identity (number, expiry) travels with it. */
    @PostMapping("/transfers")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT')")
    @Transactional
    public Map<String, Object> transfer(@AuthenticationPrincipal AuthUser u, @Valid @RequestBody Transfer in) {
        long from = stock.godown(u.tenantId(), in.fromGodownId());
        long to = stock.godown(u.tenantId(), in.toGodownId());
        if (from == to) throw ApiException.badRequest("SAME_GODOWN", "Choose two different godowns");
        long number = numbers.next(u.tenantId(), "TRF");
        long tid = db.sql("""
                INSERT INTO stock_transfers (tenant_id, number, from_godown, to_godown, note, created_by)
                VALUES (:t, :n, :f, :to, :note, :u) RETURNING id
                """).param("t", u.tenantId()).param("n", number).param("f", from).param("to", to).param("note", in.note())
                .param("u", u.userId()).query(Long.class).single();
        // lock in a stable order: product, then godown id ascending
        var lines = in.lines().stream().sorted(java.util.Comparator.comparing(TransferLine::productId)).toList();
        for (TransferLine l : lines) {
            Integer own = db.sql("SELECT count(*) FROM products WHERE id = :p AND tenant_id = :t").param("p", l.productId())
                    .param("t", u.tenantId()).query(Integer.class).single();
            if (own == 0) throw ApiException.badRequest("BAD_PRODUCT", "Item not found");
            String batchNo = null;
            LocalDate exp = null, mfg = null;
            BigDecimal mrp = null;
            if (l.batchId() != null) {
                var b = stock.batch(l.batchId());
                batchNo = (String) b.get("batch_no");
                exp = ((java.sql.Date) b.get("expiry_date")).toLocalDate();
                mfg = b.get("mfg_date") == null ? null : ((java.sql.Date) b.get("mfg_date")).toLocalDate();
                mrp = (BigDecimal) b.get("mrp");
            }
            if (from < to) {
                stock.remove(u.tenantId(), l.productId(), from, l.qty(), l.batchId());
                stock.receive(u.tenantId(), l.productId(), to, l.qty(), batchNo, exp, mfg, mrp);
            } else {
                stock.receive(u.tenantId(), l.productId(), to, l.qty(), batchNo, exp, mfg, mrp);
                stock.remove(u.tenantId(), l.productId(), from, l.qty(), l.batchId());
            }
            db.sql("INSERT INTO transfer_lines (transfer_id, product_id, qty, batch_no) VALUES (:tr, :p, :q, :b)")
                    .param("tr", tid).param("p", l.productId()).param("q", l.qty()).param("b", batchNo).update();
        }
        activity.log(u.tenantId(), u.userId(), "STOCK_TRANSFER", "Transfer TRF-" + number + ": " + lines.size() + " items moved", "transfer", tid);
        return Map.of("id", tid, "number", "TRF-" + number);
    }

    static String[] validPatterns(List<String> pats) {
        if (pats == null) return new String[0];
        return pats.stream().map(String::trim).filter(s -> !s.isEmpty() && !s.contains("\n")).peek(p -> {
            try {
                java.util.regex.Pattern.compile(p);
            } catch (PatternSyntaxException e) {
                throw ApiException.badRequest("BAD_PATTERN", "Invalid match pattern: " + p);
            }
        }).toArray(String[]::new);
    }
}
