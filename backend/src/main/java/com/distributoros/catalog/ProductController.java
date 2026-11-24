package com.distributoros.catalog;

import com.distributoros.common.Activity;
import com.distributoros.common.ApiException;
import com.distributoros.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

@RestController
@RequestMapping("/api")
public class ProductController {
    private final JdbcClient db;
    private final Activity activity;

    public ProductController(JdbcClient db, Activity activity) { this.db = db; this.activity = activity; }

    @GetMapping("/inventory")
    public List<Map<String, Object>> inventory(@AuthenticationPrincipal AuthUser u) {
        return db.sql("""
                SELECT p.id, p.sku, p.name, p.unit, p.category, p.list_rate AS "listRate", p.gst_rate AS "gstRate",
                       p.reorder_level AS "reorderLevel", array_to_string(p.match_patterns, E'\\n') AS patterns,
                       COALESCE(s.on_hand, 0) AS "onHand", COALESCE(s.reserved, 0) AS reserved,
                       COALESCE(s.on_hand - s.reserved, 0) AS free
                FROM products p LEFT JOIN stock s ON s.product_id = p.id
                WHERE p.tenant_id = :t AND p.active
                ORDER BY (COALESCE(s.on_hand - s.reserved, 0)::numeric / GREATEST(p.reorder_level, 1)), p.sku
                """).param("t", u.tenantId()).query().listOfRows();
    }

    public record ProductInput(@NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9_\\-/.]+") String sku,
                               @NotBlank @Size(max = 200) String name,
                               @NotBlank @Size(max = 40) String unit,
                               @NotBlank @Size(max = 60) String category,
                               @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal listRate,
                               @NotNull @DecimalMin("0") @DecimalMax("28") BigDecimal gstRate,
                               @NotNull @PositiveOrZero Integer reorderLevel,
                               @Size(max = 10) List<@Size(max = 200) String> patterns) {}

    @PostMapping("/products")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public Map<String, Object> create(@AuthenticationPrincipal AuthUser u, @Valid @RequestBody ProductInput in) {
        String pats = String.join("\n", validPatterns(in.patterns()));
        Long id = db.sql("""
                INSERT INTO products (tenant_id, sku, name, unit, category, list_rate, gst_rate, reorder_level, match_patterns)
                VALUES (:t, :sku, :n, :unit, :cat, :rate, :gst, :ro, string_to_array(:pats, E'\\n')) RETURNING id
                """).param("t", u.tenantId()).param("sku", in.sku().toUpperCase()).param("n", in.name().trim())
                .param("unit", in.unit()).param("cat", in.category()).param("rate", in.listRate())
                .param("gst", in.gstRate()).param("ro", in.reorderLevel()).param("pats", pats)
                .query(Long.class).single();
        db.sql("INSERT INTO stock (product_id, tenant_id, on_hand) VALUES (:p, :t, 0)")
                .param("p", id).param("t", u.tenantId()).update();
        activity.log(u.tenantId(), u.userId(), "PRODUCT_CREATED", "Item " + in.sku() + " added", "product", id);
        return Map.of("id", id);
    }

    @PutMapping("/products/{id}")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public Map<String, Object> update(@AuthenticationPrincipal AuthUser u, @PathVariable long id,
                                      @Valid @RequestBody ProductInput in) {
        String pats = String.join("\n", validPatterns(in.patterns()));
        int n = db.sql("""
                UPDATE products SET sku = :sku, name = :n, unit = :unit, category = :cat, list_rate = :rate,
                       gst_rate = :gst, reorder_level = :ro, match_patterns = string_to_array(:pats, E'\\n')
                WHERE id = :id AND tenant_id = :t
                """).param("sku", in.sku().toUpperCase()).param("n", in.name().trim()).param("unit", in.unit())
                .param("cat", in.category()).param("rate", in.listRate()).param("gst", in.gstRate())
                .param("ro", in.reorderLevel()).param("pats", pats).param("id", id).param("t", u.tenantId()).update();
        if (n == 0) throw ApiException.notFound("Product");
        return Map.of("ok", true);
    }

    public record StockAdjust(@NotNull @Min(-1_000_000) @Max(1_000_000) Integer delta,
                              @NotBlank @Size(max = 200) String reason) {}

    @PostMapping("/products/{id}/stock")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT')")
    @Transactional
    public Map<String, Object> adjust(@AuthenticationPrincipal AuthUser u, @PathVariable long id,
                                      @Valid @RequestBody StockAdjust in) {
        var rows = db.sql("""
                UPDATE stock SET on_hand = on_hand + :d, updated_at = now()
                WHERE product_id = :p AND tenant_id = :t AND on_hand + :d >= reserved
                RETURNING on_hand
                """).param("d", in.delta()).param("p", id).param("t", u.tenantId()).query(Integer.class).list();
        if (rows.isEmpty()) {
            throw ApiException.conflict("STOCK_BELOW_RESERVED",
                    "Stock cannot go below what is already reserved for open orders");
        }
        activity.log(u.tenantId(), u.userId(), "STOCK_ADJUSTED",
                "Stock adjusted by " + in.delta() + " (" + in.reason() + ")", "product", id);
        return Map.of("onHand", rows.get(0));
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
