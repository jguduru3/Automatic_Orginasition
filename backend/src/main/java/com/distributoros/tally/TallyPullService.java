package com.distributoros.tally;

import com.distributoros.catalog.StockService;
import com.distributoros.common.Activity;
import com.distributoros.common.ApiException;
import com.distributoros.config.AppProperties;
import com.distributoros.integrations.TallyClient;
import com.distributoros.integrations.TallyMasters;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pulls stock items and debtor ledgers from Tally. Items are matched by Tally name, then by item name; customers by
 * ledger name, then phone, then name. New customers are created with a zero credit limit, so every order from them
 * is held until someone sets a limit. Stock (optional) is set to Tally's closing balance in the default godown for
 * items without batch tracking, never below what is reserved.
 */
@Service
public class TallyPullService {
    private final JdbcClient db;
    private final TallyClient tally;
    private final StockService stock;
    private final Activity activity;
    private final AppProperties props;

    public TallyPullService(JdbcClient db, TallyClient tally, StockService stock, Activity activity, AppProperties props) {
        this.db = db; this.tally = tally; this.stock = stock; this.activity = activity; this.props = props;
    }

    @Transactional
    public Map<String, Object> pull(long tenantId, long userId, boolean applyStock) {
        List<TallyMasters.StockItem> items;
        List<TallyMasters.Ledger> ledgers;
        try {
            items = TallyMasters.stockItems(tally.export(TallyMasters.stockItemsRequest(props.tally().company())));
            ledgers = TallyMasters.ledgers(tally.export(TallyMasters.ledgersRequest(props.tally().company())));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "TALLY_UNREACHABLE", "Could not read from Tally: " + e.getMessage());
        }
        long godown = stock.defaultGodown(tenantId);
        int itemsMatched = 0, itemsCreated = 0, stockUpdated = 0, stockSkipped = 0;
        for (TallyMasters.StockItem it : items) {
            Long pid = db.sql("""
                    SELECT id FROM products WHERE tenant_id = :t AND (tally_name = :n OR (tally_name IS NULL AND lower(name) = lower(:n)))
                    ORDER BY (tally_name = :n) DESC NULLS LAST LIMIT 1
                    """).param("t", tenantId).param("n", it.name()).query(Long.class).optional().orElse(null);
            if (pid != null) {
                db.sql("UPDATE products SET tally_name = :n WHERE id = :p AND tally_name IS NULL").param("n", it.name()).param("p", pid).update();
                itemsMatched++;
            } else {
                pid = db.sql("""
                        INSERT INTO products (tenant_id, sku, name, unit, category, list_rate, tally_name)
                        VALUES (:t, :sku, :n, :u, :c, :r, :n) RETURNING id
                        """).param("t", tenantId).param("sku", uniqueSku(tenantId, it.name())).param("n", it.name())
                        .param("u", it.unit().isBlank() ? "nos" : it.unit()).param("c", it.group().isBlank() ? "From Tally" : it.group())
                        .param("r", it.rate() == null ? BigDecimal.ZERO : it.rate().setScale(2, RoundingMode.HALF_UP))
                        .query(Long.class).single();
                itemsCreated++;
            }
            if (applyStock && it.closingQty() != null) {
                boolean batch = db.sql("SELECT batch_tracked FROM products WHERE id = :p").param("p", pid).query(Boolean.class).single();
                int qty = it.closingQty().setScale(0, RoundingMode.DOWN).intValue();
                if (batch || qty < 0) { stockSkipped++; continue; }
                int cur = db.sql("SELECT COALESCE((SELECT on_hand FROM stock WHERE product_id = :p AND godown_id = :g), 0)")
                        .param("p", pid).param("g", godown).query(Integer.class).single();
                if (qty == cur) continue;
                try {
                    if (qty > cur) stock.receive(tenantId, pid, godown, qty - cur, null, null, null, null);
                    else stock.remove(tenantId, pid, godown, cur - qty, null);
                    stockUpdated++;
                } catch (ApiException e) {
                    stockSkipped++; // would go below reserved stock
                }
            }
        }
        int custMatched = 0, custCreated = 0, custSkipped = 0;
        String debtors = props.tally().debtorsGroup();
        Long tier = db.sql("SELECT id FROM price_tiers WHERE tenant_id = :t ORDER BY discount_pct, code LIMIT 1")
                .param("t", tenantId).query(Long.class).optional().orElse(null);
        for (TallyMasters.Ledger l : ledgers) {
            if (debtors != null && !debtors.equalsIgnoreCase(l.group())) continue;
            String phone = TallyMasters.phone(l.mobile());
            Long cid = db.sql("""
                    SELECT id FROM customers WHERE tenant_id = :t AND (tally_ledger = :n OR (tally_ledger IS NULL AND (phone = :p OR lower(name) = lower(:n))))
                    ORDER BY (tally_ledger = :n) DESC NULLS LAST, (phone = :p) DESC NULLS LAST LIMIT 1
                    """).param("t", tenantId).param("n", l.name()).param("p", phone == null ? "" : phone).query(Long.class).optional().orElse(null);
            if (cid != null) {
                db.sql("UPDATE customers SET tally_ledger = :n, gstin = COALESCE(gstin, NULLIF(:g, '')) WHERE id = :c AND tally_ledger IS NULL")
                        .param("n", l.name()).param("g", l.gstin()).param("c", cid).update();
                custMatched++;
            } else if (phone != null) {
                Integer phoneTaken = db.sql("SELECT count(*) FROM customers WHERE tenant_id = :t AND phone = :p")
                        .param("t", tenantId).param("p", phone).query(Integer.class).single();
                if (phoneTaken > 0) { custSkipped++; continue; }
                db.sql("""
                        INSERT INTO customers (tenant_id, name, phone, gstin, tier_id, credit_limit, tally_ledger)
                        VALUES (:t, :n, :p, NULLIF(:g, ''), :tier, 0, :n)
                        """).param("t", tenantId).param("n", l.name()).param("p", phone).param("g", l.gstin()).param("tier", tier).update();
                custCreated++;
            } else {
                custSkipped++; // no mobile number: cannot be reached on WhatsApp
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("itemsMatched", itemsMatched);
        r.put("itemsCreated", itemsCreated);
        r.put("stockUpdated", stockUpdated);
        r.put("stockSkipped", stockSkipped);
        r.put("customersMatched", custMatched);
        r.put("customersCreated", custCreated);
        r.put("customersSkipped", custSkipped);
        String summary = itemsMatched + itemsCreated + " items (" + itemsCreated + " new), " + stockUpdated + " stock updates, "
                + (custMatched + custCreated) + " customers (" + custCreated + " new, " + custSkipped + " without mobile skipped)";
        db.sql("UPDATE tenants SET tally_last_pull_at = now(), tally_last_pull_summary = :s WHERE id = :t")
                .param("s", summary).param("t", tenantId).update();
        activity.log(tenantId, userId, "TALLY_PULL", "Pulled from Tally: " + summary, null, null);
        r.put("summary", summary);
        return r;
    }

    private String uniqueSku(long tenantId, String name) {
        String base = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (base.isEmpty()) base = "ITEM";
        base = base.substring(0, Math.min(10, base.length()));
        String sku = base;
        for (int i = 2; ; i++) {
            Integer n = db.sql("SELECT count(*) FROM products WHERE tenant_id = :t AND sku = :s").param("t", tenantId).param("s", sku)
                    .query(Integer.class).single();
            if (n == 0) return sku;
            sku = base + "-" + i;
        }
    }
}
