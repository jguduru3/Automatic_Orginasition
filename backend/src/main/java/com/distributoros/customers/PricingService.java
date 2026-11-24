package com.distributoros.customers;

import com.distributoros.common.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Customer-specific rate: special project price, else list rate less the customer's tier discount (rounded to rupees). */
@Service
public class PricingService {
    private final JdbcClient db;

    public PricingService(JdbcClient db) { this.db = db; }

    public record Quote(long productId, String sku, String name, String unit, BigDecimal rate, BigDecimal gstRate,
                        String source, int free, boolean requiresLicence, boolean batchTracked) {}

    public Map<Long, Quote> quote(long tenantId, long customerId, long godownId, Collection<Long> productIds) {
        Map<Long, Quote> out = new HashMap<>();
        if (productIds.isEmpty()) return out;
        List<Map<String, Object>> rows = db.sql("""
                SELECT p.id, p.sku, p.name, p.unit, p.list_rate, p.gst_rate, cp.rate AS special,
                       COALESCE(pt.discount_pct, 0) AS disc, pt.name AS tier_name,
                       p.requires_licence, p.batch_tracked,
                       """ + com.distributoros.catalog.StockService.freeIn(":g") + """
                         AS free
                FROM products p
                JOIN customers c ON c.id = :c AND c.tenant_id = p.tenant_id
                LEFT JOIN price_tiers pt ON pt.id = c.tier_id
                LEFT JOIN customer_prices cp ON cp.customer_id = c.id AND cp.product_id = p.id
                WHERE p.tenant_id = :t AND p.active AND p.id IN (:ids)
                """).param("t", tenantId).param("c", customerId).param("ids", productIds).param("g", godownId)
                .query().listOfRows();
        for (Map<String, Object> r : rows) {
            BigDecimal list = Money.of(r.get("list_rate"));
            BigDecimal disc = Money.of(r.get("disc"));
            BigDecimal rate;
            String src;
            if (r.get("special") != null) {
                rate = Money.of(r.get("special"));
                src = "Project price";
            } else if (disc.signum() > 0) {
                rate = Money.paise(list.multiply(BigDecimal.ONE.subtract(disc.movePointLeft(2))));
                src = r.get("tier_name") + " -" + disc.stripTrailingZeros().toPlainString() + "%";
            } else {
                rate = Money.paise(list);
                src = "List price";
            }
            long id = ((Number) r.get("id")).longValue();
            out.put(id, new Quote(id, (String) r.get("sku"), (String) r.get("name"), (String) r.get("unit"), rate,
                    Money.of(r.get("gst_rate")), src, ((Number) r.get("free")).intValue(),
                    Boolean.TRUE.equals(r.get("requires_licence")), Boolean.TRUE.equals(r.get("batch_tracked"))));
        }
        return out;
    }
}
