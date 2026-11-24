package com.distributoros.collections;

import com.distributoros.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CollectionsController {
    private final JdbcClient db;
    private final PaymentService payments;

    public CollectionsController(JdbcClient db, PaymentService payments) { this.db = db; this.payments = payments; }

    @GetMapping("/collections")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT','SALES')")
    public List<Map<String, Object>> aging(@AuthenticationPrincipal AuthUser u) {
        return db.sql("""
                SELECT c.id AS "customerId", c.name AS customer, c.phone, u.name AS salesperson,
                  sum(CASE WHEN CURRENT_DATE - i.invoice_date <= 30 THEN i.amount - i.paid ELSE 0 END) AS d0,
                  sum(CASE WHEN CURRENT_DATE - i.invoice_date BETWEEN 31 AND 60 THEN i.amount - i.paid ELSE 0 END) AS d30,
                  sum(CASE WHEN CURRENT_DATE - i.invoice_date BETWEEN 61 AND 90 THEN i.amount - i.paid ELSE 0 END) AS d60,
                  sum(CASE WHEN CURRENT_DATE - i.invoice_date > 90 THEN i.amount - i.paid ELSE 0 END) AS d90,
                  sum(i.amount - i.paid) AS total,
                  max(CURRENT_DATE - i.invoice_date) AS "oldestDays"
                FROM invoices i JOIN customers c ON c.id = i.customer_id LEFT JOIN users u ON u.id = c.salesperson_id
                WHERE i.tenant_id = :t AND i.paid < i.amount
                GROUP BY c.id, c.name, c.phone, u.name
                ORDER BY sum(CASE WHEN CURRENT_DATE - i.invoice_date > 30 THEN i.amount - i.paid ELSE 0 END) DESC, total DESC
                """).param("t", u.tenantId()).query().listOfRows();
    }

    @GetMapping("/payments/today")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT','SALES')")
    public List<Map<String, Object>> today(@AuthenticationPrincipal AuthUser u) {
        return db.sql("""
                SELECT p.id, 'RCPT-' || p.number AS receipt, c.name AS customer, p.amount, p.mode, p.reference,
                       p.received_at AS "receivedAt",
                       (SELECT string_agg('INV-' || i.number, ', ') FROM payment_allocations a JOIN invoices i ON i.id = a.invoice_id
                         WHERE a.payment_id = p.id) AS "appliedTo"
                FROM payments p JOIN customers c ON c.id = p.customer_id
                WHERE p.tenant_id = :t AND p.received_at >= date_trunc('day', now())
                ORDER BY p.id DESC
                """).param("t", u.tenantId()).query().listOfRows();
    }

    public record PaymentInput(@NotNull Long customerId,
                               @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
                               @NotBlank @Pattern(regexp = "UPI|NEFT|CHEQUE|CASH") String mode,
                               @Size(max = 100) String reference,
                               @Size(max = 100) String idempotencyKey) {}

    @PostMapping("/payments")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT','SALES')")
    public Map<String, Object> record(@AuthenticationPrincipal AuthUser u, @Valid @RequestBody PaymentInput in) {
        return payments.record(u, in.customerId(), in.amount(), in.mode(), in.reference(), in.idempotencyKey());
    }

    @PostMapping("/customers/{id}/remind")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT','SALES')")
    public Map<String, Object> remind(@AuthenticationPrincipal AuthUser u, @PathVariable long id) {
        return payments.remind(u, id);
    }
}
