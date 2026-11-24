package com.distributoros.customers;

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

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final JdbcClient db;
    private final Activity activity;

    public CustomerController(JdbcClient db, Activity activity) { this.db = db; this.activity = activity; }

    static final String LIST_SQL = """
            SELECT c.id, c.name, c.town, c.phone, c.gstin, c.credit_limit AS "creditLimit",
                   pt.code AS "tierCode", pt.name AS "tierName", pt.discount_pct AS "tierDiscount",
                   u.id AS "salespersonId", u.name AS "salesperson",
                   COALESCE(inv.outstanding, 0) AS outstanding,
                   COALESCE(inv.oldest, 0) AS "oldestDays",
                   COALESCE(oo.open_total, 0) AS "openOrders",
                   COALESCE(inv.outstanding, 0) + COALESCE(oo.open_total, 0) AS exposure,
                   (SELECT string_agg(p.sku, ', ' ORDER BY p.sku) FROM customer_prices cp JOIN products p ON p.id = cp.product_id
                     WHERE cp.customer_id = c.id) AS "specialSkus"
            FROM customers c
            LEFT JOIN price_tiers pt ON pt.id = c.tier_id
            LEFT JOIN users u ON u.id = c.salesperson_id
            LEFT JOIN LATERAL (SELECT sum(amount - paid) outstanding, max(CURRENT_DATE - invoice_date) oldest
                               FROM invoices i WHERE i.customer_id = c.id AND i.paid < i.amount) inv ON TRUE
            LEFT JOIN LATERAL (SELECT sum(total) open_total FROM sales_orders o
                               WHERE o.customer_id = c.id AND o.status IN ('CONFIRMED','PACKED')) oo ON TRUE
            WHERE c.tenant_id = :t AND c.active
            """;

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthUser u,
                                          @RequestParam(required = false) String q) {
        String sql = LIST_SQL + (q == null || q.isBlank() ? "" : " AND (c.name ILIKE :q OR c.phone LIKE :q OR c.town ILIKE :q)")
                + " ORDER BY c.name LIMIT 500";
        var stmt = db.sql(sql).param("t", u.tenantId());
        if (q != null && !q.isBlank()) stmt = stmt.param("q", "%" + q.trim().replace("%", "") + "%");
        return stmt.query().listOfRows();
    }

    public record CustomerInput(@NotBlank @Size(max = 200) String name,
                                @Size(max = 100) String town,
                                @NotBlank @Pattern(regexp = "\\d{10,15}") String phone,
                                @Size(max = 15) String gstin,
                                @NotBlank @Size(max = 10) String tierCode,
                                @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal creditLimit,
                                Long salespersonId) {}

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT')")
    @Transactional
    public Map<String, Object> create(@AuthenticationPrincipal AuthUser u, @Valid @RequestBody CustomerInput in) {
        Long tier = tierId(u.tenantId(), in.tierCode());
        Long id = db.sql("""
                INSERT INTO customers (tenant_id, name, town, phone, gstin, tier_id, credit_limit, salesperson_id)
                VALUES (:t, :n, :town, :p, :g, :tier, :lim, :sp) RETURNING id
                """).param("t", u.tenantId()).param("n", in.name().trim()).param("town", in.town())
                .param("p", in.phone()).param("g", in.gstin()).param("tier", tier).param("lim", in.creditLimit())
                .param("sp", salesperson(u.tenantId(), in.salespersonId()))
                .query(Long.class).single();
        db.sql("UPDATE conversations SET customer_id = :c WHERE tenant_id = :t AND phone = :p AND customer_id IS NULL")
                .param("c", id).param("t", u.tenantId()).param("p", in.phone()).update();
        activity.log(u.tenantId(), u.userId(), "CUSTOMER_CREATED", "Customer " + in.name() + " added", "customer", id);
        return Map.of("id", id);
    }

    public record CreditInput(@NotNull @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal creditLimit,
                              @NotBlank @Size(max = 10) String tierCode) {}

    @PutMapping("/{id}/credit")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT')")
    @Transactional
    public Map<String, Object> updateCredit(@AuthenticationPrincipal AuthUser u, @PathVariable long id,
                                            @Valid @RequestBody CreditInput in) {
        Long tier = tierId(u.tenantId(), in.tierCode());
        int n = db.sql("UPDATE customers SET credit_limit = :l, tier_id = :tier WHERE id = :id AND tenant_id = :t")
                .param("l", in.creditLimit()).param("tier", tier).param("id", id).param("t", u.tenantId()).update();
        if (n == 0) throw ApiException.notFound("Customer");
        activity.log(u.tenantId(), u.userId(), "CREDIT_CHANGED",
                "Credit limit set to Rs " + in.creditLimit().toPlainString() + ", " + in.tierCode(), "customer", id);
        return Map.of("ok", true);
    }

    public record CustomerDetails(@NotBlank @Size(max = 200) String name, @Size(max = 100) String town,
                                  @Size(max = 15) String gstin, Long salespersonId) {}

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT')")
    @Transactional
    public Map<String, Object> update(@AuthenticationPrincipal AuthUser u, @PathVariable long id,
                                      @Valid @RequestBody CustomerDetails in) {
        int n = db.sql("UPDATE customers SET name = :n, town = :town, gstin = :g, salesperson_id = :sp WHERE id = :id AND tenant_id = :t")
                .param("n", in.name().trim()).param("town", in.town()).param("g", in.gstin())
                .param("sp", salesperson(u.tenantId(), in.salespersonId())).param("id", id).param("t", u.tenantId()).update();
        if (n == 0) throw ApiException.notFound("Customer");
        return Map.of("ok", true);
    }

    @GetMapping("/{id}/prices")
    public List<Map<String, Object>> prices(@AuthenticationPrincipal AuthUser u, @PathVariable long id) {
        return db.sql("""
                SELECT p.id AS "productId", p.sku, p.name, cp.rate FROM customer_prices cp
                JOIN products p ON p.id = cp.product_id JOIN customers c ON c.id = cp.customer_id
                WHERE cp.customer_id = :c AND c.tenant_id = :t ORDER BY p.sku
                """).param("c", id).param("t", u.tenantId()).query().listOfRows();
    }

    public record SpecialPrice(@NotNull Long productId, @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal rate) {}

    /** Replaces the customer's special (project) rates. An empty list removes them all. */
    @PutMapping("/{id}/prices")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT')")
    @Transactional
    public Map<String, Object> setPrices(@AuthenticationPrincipal AuthUser u, @PathVariable long id,
                                         @Valid @RequestBody @Size(max = 500) List<@Valid SpecialPrice> in) {
        Integer own = db.sql("SELECT count(*) FROM customers WHERE id = :c AND tenant_id = :t").param("c", id)
                .param("t", u.tenantId()).query(Integer.class).single();
        if (own == 0) throw ApiException.notFound("Customer");
        db.sql("DELETE FROM customer_prices WHERE customer_id = :c").param("c", id).update();
        for (SpecialPrice sp : in) {
            int ok = db.sql("""
                    INSERT INTO customer_prices (customer_id, product_id, rate)
                    SELECT :c, p.id, :r FROM products p WHERE p.id = :p AND p.tenant_id = :t
                    ON CONFLICT (customer_id, product_id) DO UPDATE SET rate = EXCLUDED.rate
                    """).param("c", id).param("r", sp.rate()).param("p", sp.productId()).param("t", u.tenantId()).update();
            if (ok == 0) throw ApiException.badRequest("BAD_PRODUCT", "Item " + sp.productId() + " not found");
        }
        activity.log(u.tenantId(), u.userId(), "PRICES_CHANGED", in.size() + " special rates set", "customer", id);
        return Map.of("ok", true, "count", in.size());
    }

    @GetMapping("/tiers")
    public List<Map<String, Object>> tiers(@AuthenticationPrincipal AuthUser u) {
        return db.sql("SELECT code, name, discount_pct AS discount FROM price_tiers WHERE tenant_id = :t ORDER BY code")
                .param("t", u.tenantId()).query().listOfRows();
    }

    private Long tierId(long tenantId, String code) {
        return db.sql("SELECT id FROM price_tiers WHERE tenant_id = :t AND code = :c")
                .param("t", tenantId).param("c", code).query(Long.class).optional()
                .orElseThrow(() -> ApiException.badRequest("BAD_TIER", "Unknown price tier " + code));
    }

    private Long salesperson(long tenantId, Long id) {
        if (id == null) return null;
        return db.sql("SELECT id FROM users WHERE id = :id AND tenant_id = :t AND role = 'SALES'")
                .param("id", id).param("t", tenantId).query(Long.class).optional()
                .orElseThrow(() -> ApiException.badRequest("BAD_SALESPERSON", "Salesperson not found"));
    }
}
