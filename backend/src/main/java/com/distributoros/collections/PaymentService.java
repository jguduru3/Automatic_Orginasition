package com.distributoros.collections;

import com.distributoros.common.Activity;
import com.distributoros.common.ApiException;
import com.distributoros.common.DocNumbers;
import com.distributoros.common.Money;
import com.distributoros.config.AppProperties;
import com.distributoros.customers.CreditService;
import com.distributoros.integrations.TallyXml;
import com.distributoros.orders.OrderService;
import com.distributoros.security.AuthUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class PaymentService {
    private final JdbcClient db;
    private final CreditService credit;
    private final DocNumbers numbers;
    private final Activity activity;
    private final AppProperties props;
    private final OrderService orders;

    public PaymentService(JdbcClient db, CreditService credit, DocNumbers numbers, Activity activity,
                          AppProperties props, OrderService orders) {
        this.db = db; this.credit = credit; this.numbers = numbers; this.activity = activity;
        this.props = props; this.orders = orders;
    }

    /**
     * Records a receipt and knocks off the oldest open bills first. Idempotent on idempotencyKey so a retried
     * request (flaky mobile network) never double-counts money. Any amount beyond open bills stays unapplied (advance).
     */
    @Transactional
    public Map<String, Object> record(AuthUser u, long customerId, BigDecimal amount, String mode, String reference,
                                      String idempotencyKey) {
        if (amount == null || amount.signum() <= 0) throw ApiException.badRequest("BAD_AMOUNT", "Amount must be more than zero");
        amount = Money.paise(amount);
        credit.lockCustomer(u.tenantId(), customerId);
        if (idempotencyKey != null) {
            var existing = db.sql("SELECT id, number FROM payments WHERE tenant_id = :t AND idempotency_key = :k")
                    .param("t", u.tenantId()).param("k", idempotencyKey).query().listOfRows();
            if (!existing.isEmpty()) {
                return Map.of("id", existing.get(0).get("id"), "receipt", "RCPT-" + existing.get(0).get("number"), "duplicate", true);
            }
        }
        long number = numbers.next(u.tenantId(), "RCPT");
        long paymentId = db.sql("""
                INSERT INTO payments (tenant_id, number, customer_id, amount, mode, reference, idempotency_key, created_by)
                VALUES (:t, :n, :c, :a, :m, :r, :k, :u) RETURNING id
                """).param("t", u.tenantId()).param("n", number).param("c", customerId).param("a", amount)
                .param("m", mode).param("r", reference).param("k", idempotencyKey).param("u", u.userId())
                .query(Long.class).single();
        var open = db.sql("""
                SELECT id, number, amount - paid AS due FROM invoices
                WHERE customer_id = :c AND tenant_id = :t AND paid < amount
                ORDER BY invoice_date, id FOR UPDATE
                """).param("c", customerId).param("t", u.tenantId()).query().listOfRows();
        BigDecimal left = amount;
        List<String> applied = new ArrayList<>();
        for (var inv : open) {
            if (left.signum() <= 0) break;
            BigDecimal due = Money.of(inv.get("due"));
            BigDecimal x = due.min(left);
            long invId = ((Number) inv.get("id")).longValue();
            db.sql("UPDATE invoices SET paid = paid + :x WHERE id = :id").param("x", x).param("id", invId).update();
            db.sql("INSERT INTO payment_allocations (payment_id, invoice_id, amount) VALUES (:p, :i, :x)")
                    .param("p", paymentId).param("i", invId).param("x", x).update();
            applied.add("INV-" + inv.get("number"));
            left = left.subtract(x);
        }
        if (left.signum() > 0) {
            db.sql("UPDATE payments SET unapplied = :l WHERE id = :id").param("l", left).param("id", paymentId).update();
        }
        String party = db.sql("SELECT name FROM customers WHERE id = :c").param("c", customerId).query(String.class).single();
        String ref = "RCPT-" + number;
        String narration = mode + (reference == null ? "" : " " + reference) + (applied.isEmpty() ? "" : " against " + String.join(", ", applied));
        db.sql("""
                INSERT INTO tally_outbox (tenant_id, voucher_type, ref, party, amount, payload)
                VALUES (:t, 'RECEIPT', :r, :p, :a, :x)
                """).param("t", u.tenantId()).param("r", ref).param("p", party).param("a", amount)
                .param("x", TallyXml.receipt(props.tally().company(), LocalDate.now(), ref, party, props.tally().bankLedger(),
                        amount, narration)).update();
        activity.log(u.tenantId(), u.userId(), "PAYMENT_RECEIVED",
                "Rs " + amount.toPlainString() + " received from " + party + " by " + mode, "payment", paymentId);
        return Map.of("id", paymentId, "receipt", ref, "appliedTo", applied, "unapplied", left);
    }

    @Transactional
    public Map<String, Object> remind(AuthUser u, long customerId) {
        var rows = db.sql("""
                SELECT c.name, c.phone,
                  (SELECT sum(amount - paid) FROM invoices i WHERE i.customer_id = c.id AND i.paid < i.amount) AS due,
                  (SELECT 'INV-' || number || ', ' || (CURRENT_DATE - invoice_date) || ' days' FROM invoices i
                    WHERE i.customer_id = c.id AND i.paid < i.amount ORDER BY invoice_date, id LIMIT 1) AS oldest
                FROM customers c WHERE c.id = :c AND c.tenant_id = :t
                """).param("c", customerId).param("t", u.tenantId()).query().listOfRows();
        if (rows.isEmpty()) throw ApiException.notFound("Customer");
        var r = rows.get(0);
        if (r.get("due") == null) throw ApiException.badRequest("NOTHING_DUE", "This customer has nothing outstanding");
        String tenant = db.sql("SELECT name FROM tenants WHERE id = :t").param("t", u.tenantId()).query(String.class).single();
        String body = "Namaste " + r.get("name") + ", a reminder from " + tenant + ".\nOutstanding: Rs "
                + Money.of(r.get("due")).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString()
                + "\nOldest bill: " + r.get("oldest") + "\nPlease pay by UPI or NEFT. Thank you.";
        long conv = db.sql("""
                INSERT INTO conversations (tenant_id, customer_id, phone, status) VALUES (:t, :c, :p, 'CONVERTED')
                ON CONFLICT (tenant_id, phone) DO UPDATE SET last_message_at = now() RETURNING id
                """).param("t", u.tenantId()).param("c", customerId).param("p", r.get("phone")).query(Long.class).single();
        String tpl = props.whatsapp().reminderTemplate();
        String due = Money.of(r.get("due")).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
        orders.sendMessage(u.tenantId(), conv, (String) r.get("phone"), body,
                tpl == null || tpl.isBlank() ? null : tpl,
                List.of(String.valueOf(r.get("name")), "Rs " + due, String.valueOf(r.get("oldest"))));
        activity.log(u.tenantId(), u.userId(), "REMINDER_SENT", "Payment reminder sent to " + r.get("name"), "customer", customerId);
        return Map.of("ok", true);
    }
}
