package com.distributoros.dashboard;

import com.distributoros.security.AuthUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class DashboardController {
    private final JdbcClient db;

    public DashboardController(JdbcClient db) { this.db = db; }

    private Map<String, Object> one(String sql, long t) {
        return db.sql(sql).param("t", t).query().listOfRows().get(0);
    }

    private List<Map<String, Object>> many(String sql, long t) {
        return db.sql(sql).param("t", t).query().listOfRows();
    }

    @GetMapping("/api/dashboard")
    public Map<String, Object> dashboard(@AuthenticationPrincipal AuthUser u) {
        long t = u.tenantId();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("kpis", one("""
                SELECT
                  (SELECT COALESCE(sum(total),0) FROM sales_orders WHERE tenant_id = :t AND status <> 'CANCELLED'
                     AND created_at >= date_trunc('day', now())) AS "salesToday",
                  (SELECT count(*) FROM sales_orders WHERE tenant_id = :t AND status <> 'CANCELLED'
                     AND created_at >= date_trunc('day', now())) AS "ordersToday",
                  (SELECT COALESCE(sum(total),0) FROM sales_orders WHERE tenant_id = :t AND status <> 'CANCELLED'
                     AND created_at >= date_trunc('day', now()) - interval '7 days'
                     AND created_at < date_trunc('day', now()) - interval '6 days') AS "salesSameDayLastWeek",
                  (SELECT count(*) FROM sales_orders WHERE tenant_id = :t AND status IN ('CONFIRMED','PACKED')) AS "openOrders",
                  (SELECT COALESCE(sum(total),0) FROM sales_orders WHERE tenant_id = :t AND status IN ('CONFIRMED','PACKED')) AS "openOrderValue",
                  (SELECT COALESCE(sum(amount - paid),0) FROM invoices WHERE tenant_id = :t AND paid < amount) AS receivables,
                  (SELECT COALESCE(sum(amount - paid),0) FROM invoices WHERE tenant_id = :t AND paid < amount
                     AND CURRENT_DATE - invoice_date > 30) AS "overdue30",
                  (SELECT COALESCE(sum(amount),0) FROM payments WHERE tenant_id = :t AND received_at >= date_trunc('month', now())) AS "collectedMtd",
                  (SELECT COALESCE(sum(total),0) FROM sales_orders WHERE tenant_id = :t AND status <> 'CANCELLED'
                     AND created_at >= date_trunc('month', now())) AS "salesMtd",
                  (SELECT count(*) FROM conversations WHERE tenant_id = :t AND status = 'NEW') AS "newMessages",
                  (SELECT count(*) FROM tally_outbox WHERE tenant_id = :t AND status = 'PENDING') AS "tallyPending"
                """, t));
        d.put("lowStock", many("""
                SELECT sku, name, free, "reorderLevel" FROM (
                  SELECT p.sku, p.name, p.reorder_level AS "reorderLevel",
                    CASE WHEN p.batch_tracked THEN COALESCE((SELECT sum(b.on_hand - b.reserved) FROM stock_batches b
                           WHERE b.product_id = p.id AND b.expiry_date > CURRENT_DATE), 0)
                         ELSE COALESCE((SELECT sum(s.on_hand - s.reserved) FROM stock s WHERE s.product_id = p.id), 0) END AS free
                  FROM products p WHERE p.tenant_id = :t AND p.active) x
                WHERE free < "reorderLevel" ORDER BY sku
                """, t));
        d.put("expiry", one("""
                SELECT COALESCE(sum(CASE WHEN b.expiry_date <= CURRENT_DATE THEN b.on_hand * p.list_rate END), 0) AS "expiredValue",
                       COALESCE(sum(CASE WHEN b.expiry_date > CURRENT_DATE AND b.expiry_date <= CURRENT_DATE + 90 THEN b.on_hand * p.list_rate END), 0) AS "expiring90Value",
                       count(*) FILTER (WHERE b.expiry_date <= CURRENT_DATE + 90) AS batches
                FROM stock_batches b JOIN products p ON p.id = b.product_id WHERE b.tenant_id = :t AND b.on_hand > 0
                """, t));
        d.put("purchases", one("""
                SELECT count(*) FILTER (WHERE status IN ('SENT','PARTIAL')) AS open,
                       COALESCE(sum(total) FILTER (WHERE status IN ('SENT','PARTIAL')), 0) AS "openValue",
                       count(*) FILTER (WHERE status IN ('SENT','PARTIAL') AND expected_date < CURRENT_DATE) AS late
                FROM purchase_orders WHERE tenant_id = :t
                """, t));
        d.put("dailySales", many("""
                SELECT to_char(d, 'YYYY-MM-DD') AS day, COALESCE(sum(o.total), 0) AS value
                FROM generate_series(date_trunc('day', now()) - interval '30 days', date_trunc('day', now()), interval '1 day') d
                LEFT JOIN sales_orders o ON o.tenant_id = :t AND o.status <> 'CANCELLED'
                     AND o.created_at >= d AND o.created_at < d + interval '1 day'
                WHERE extract(isodow FROM d) <> 7
                GROUP BY d ORDER BY d
                """, t));
        d.put("sources", many("""
                SELECT source, COALESCE(sum(total), 0) AS value FROM sales_orders
                WHERE tenant_id = :t AND status <> 'CANCELLED' AND created_at >= now() - interval '30 days'
                GROUP BY source ORDER BY value DESC
                """, t));
        d.put("aging", many("""
                SELECT b.label, b.ord, COALESCE(sum(i.amount - i.paid), 0) AS value, count(i.id) AS bills
                FROM (VALUES ('0-30 d',1,0,30),('31-60 d',2,31,60),('61-90 d',3,61,90),('90+ d',4,91,100000)) b(label,ord,lo,hi)
                LEFT JOIN invoices i ON i.tenant_id = :t AND i.paid < i.amount
                     AND CURRENT_DATE - i.invoice_date BETWEEN b.lo AND b.hi
                GROUP BY b.label, b.ord ORDER BY b.ord
                """, t));
        d.put("creditRisk", many("""
                SELECT c.id, c.name, c.credit_limit AS "limit",
                       COALESCE(inv.o, 0) + COALESCE(oo.o, 0) AS exposure
                FROM customers c
                LEFT JOIN LATERAL (SELECT sum(amount - paid) o FROM invoices i WHERE i.customer_id = c.id AND i.paid < i.amount) inv ON TRUE
                LEFT JOIN LATERAL (SELECT sum(total) o FROM sales_orders s WHERE s.customer_id = c.id AND s.status IN ('CONFIRMED','PACKED')) oo ON TRUE
                WHERE c.tenant_id = :t AND c.active AND c.credit_limit > 0
                ORDER BY (COALESCE(inv.o, 0) + COALESCE(oo.o, 0)) / c.credit_limit DESC LIMIT 5
                """, t));
        d.put("salespeople", many("""
                SELECT u.id, u.name, u.monthly_target AS target,
                  (SELECT COALESCE(sum(total), 0) FROM sales_orders o WHERE o.salesperson_id = u.id AND o.status <> 'CANCELLED'
                     AND o.created_at >= date_trunc('month', now())) AS achieved
                FROM users u WHERE u.tenant_id = :t AND u.role = 'SALES' AND u.active ORDER BY u.name
                """, t));
        d.put("activity", many("""
                SELECT at, message FROM activity_log WHERE tenant_id = :t ORDER BY at DESC, id DESC LIMIT 10
                """, t));
        return d;
    }
}
