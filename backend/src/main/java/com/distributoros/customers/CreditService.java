package com.distributoros.customers;

import com.distributoros.common.ApiException;
import com.distributoros.common.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service
public class CreditService {
    private final JdbcClient db;

    public CreditService(JdbcClient db) { this.db = db; }

    public record Credit(BigDecimal limit, BigDecimal outstanding, BigDecimal openOrders, int oldestDays) {
        public BigDecimal exposure() { return outstanding.add(openOrders); }
        public BigDecimal after(BigDecimal orderTotal) { return exposure().add(orderTotal); }
        public boolean wouldExceed(BigDecimal orderTotal) { return after(orderTotal).compareTo(limit) > 0; }
        public String status(BigDecimal orderTotal) {
            if (wouldExceed(orderTotal)) return "HOLD";
            if (oldestDays > 60 || after(orderTotal).compareTo(limit.multiply(new BigDecimal("0.85"))) > 0) return "WATCH";
            return "PASS";
        }
    }

    /** Lock the customer row so concurrent orders for the same customer are credit-checked one at a time. */
    public void lockCustomer(long tenantId, long customerId) {
        var ids = db.sql("SELECT id FROM customers WHERE id = :c AND tenant_id = :t AND active FOR UPDATE")
                .param("c", customerId).param("t", tenantId).query(Long.class).list();
        if (ids.isEmpty()) throw ApiException.notFound("Customer");
    }

    public Credit credit(long tenantId, long customerId) {
        Map<String, Object> r = db.sql("""
                SELECT c.credit_limit,
                  COALESCE((SELECT sum(amount - paid) FROM invoices i WHERE i.customer_id = c.id AND i.paid < i.amount), 0) AS outstanding,
                  COALESCE((SELECT sum(total) FROM sales_orders o WHERE o.customer_id = c.id AND o.status IN ('CONFIRMED','PACKED')), 0) AS open_orders,
                  COALESCE((SELECT max(CURRENT_DATE - invoice_date) FROM invoices i WHERE i.customer_id = c.id AND i.paid < i.amount), 0) AS oldest
                FROM customers c WHERE c.id = :c AND c.tenant_id = :t
                """).param("c", customerId).param("t", tenantId).query().listOfRows()
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Customer"));
        return new Credit(Money.of(r.get("credit_limit")), Money.of(r.get("outstanding")), Money.of(r.get("open_orders")),
                ((Number) r.get("oldest")).intValue());
    }
}
