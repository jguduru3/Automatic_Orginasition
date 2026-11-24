package com.distributoros.field;

import com.distributoros.common.ApiException;
import com.distributoros.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/field")
public class FieldController {
    private final JdbcClient db;

    public FieldController(JdbcClient db) { this.db = db; }

    @GetMapping
    public Map<String, Object> overview(@AuthenticationPrincipal AuthUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("salespeople", db.sql("""
                SELECT u.id, u.name, u.beat, u.monthly_target AS target,
                  (SELECT count(*) FROM visits v WHERE v.user_id = u.id AND v.checked_in_at >= date_trunc('day', now())) AS "visitsToday",
                  (SELECT COALESCE(sum(total), 0) FROM sales_orders o WHERE o.salesperson_id = u.id AND o.status <> 'CANCELLED'
                     AND o.created_at >= date_trunc('month', now())) AS "salesMtd",
                  (SELECT COALESCE(sum(p.amount), 0) FROM payments p JOIN customers c ON c.id = p.customer_id
                     WHERE c.salesperson_id = u.id AND p.received_at >= date_trunc('month', now())) AS "collectedMtd",
                  (SELECT COALESCE(c.name, 'Office') || ' · ' || to_char(v.checked_in_at, 'HH24:MI') FROM visits v
                     LEFT JOIN customers c ON c.id = v.customer_id WHERE v.user_id = u.id ORDER BY v.checked_in_at DESC LIMIT 1) AS "lastCheckIn"
                FROM users u WHERE u.tenant_id = :t AND u.role = 'SALES' AND u.active ORDER BY u.name
                """).param("t", u.tenantId()).query().listOfRows());
        m.put("deliveries", db.sql("""
                SELECT o.id, o.number, o.status, o.total, o.dispatched_at AS "dispatchedAt", o.delivered_at AS "deliveredAt",
                       o.pod_blob AS "podPath", c.name AS customer, c.town, v.reg_no AS vehicle, v.type AS "vehicleType",
                       v.driver_name AS driver, 'INV-' || i.number AS invoice
                FROM sales_orders o JOIN customers c ON c.id = o.customer_id
                LEFT JOIN vehicles v ON v.id = o.vehicle_id LEFT JOIN invoices i ON i.order_id = o.id
                WHERE o.tenant_id = :t AND (o.status = 'DISPATCHED' OR (o.status = 'DELIVERED' AND o.delivered_at >= date_trunc('day', now())))
                ORDER BY o.status, o.dispatched_at DESC
                """).param("t", u.tenantId()).query().listOfRows());
        return m;
    }

    @GetMapping("/salespeople")
    public List<Map<String, Object>> salespeople(@AuthenticationPrincipal AuthUser u) {
        return db.sql("SELECT id, name FROM users WHERE tenant_id = :t AND role = 'SALES' AND active ORDER BY name")
                .param("t", u.tenantId()).query().listOfRows();
    }

    @GetMapping("/visits/today")
    public List<Map<String, Object>> visitsToday(@AuthenticationPrincipal AuthUser u) {
        String mine = "SALES".equals(u.role()) ? " AND v.user_id = :u" : "";
        var st = db.sql("""
                SELECT v.id, u.name AS salesperson, c.name AS customer, v.note, v.lat, v.lng, v.checked_in_at AS "checkedInAt"
                FROM visits v JOIN users u ON u.id = v.user_id LEFT JOIN customers c ON c.id = v.customer_id
                WHERE v.tenant_id = :t AND v.checked_in_at >= date_trunc('day', now())""" + mine + " ORDER BY v.checked_in_at DESC LIMIT 200")
                .param("t", u.tenantId());
        if (!mine.isEmpty()) st = st.param("u", u.userId());
        return st.query().listOfRows();
    }

    public record Ping(@NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
                       @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
                       @PositiveOrZero @DecimalMax("100000") Double accuracy) {}

    /** Live location from the field app. Pings closer than 30 seconds apart are dropped to protect the database. */
    @PostMapping("/location")
    @PreAuthorize("hasAnyRole('SALES','DELIVERY')")
    public Map<String, Object> ping(@AuthenticationPrincipal AuthUser u, @Valid @RequestBody Ping in) {
        int n = db.sql("""
                INSERT INTO location_pings (tenant_id, user_id, lat, lng, accuracy_m)
                SELECT :t, :u, :la, :ln, :acc
                WHERE NOT EXISTS (SELECT 1 FROM location_pings WHERE tenant_id = :t AND user_id = :u
                                  AND recorded_at > now() - interval '30 seconds')
                """).param("t", u.tenantId()).param("u", u.userId()).param("la", in.lat()).param("ln", in.lng())
                .param("acc", in.accuracy()).update();
        return Map.of("stored", n > 0);
    }

    /** Last known position of everyone sharing location in the past 12 hours. */
    @GetMapping("/live")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT')")
    public List<Map<String, Object>> live(@AuthenticationPrincipal AuthUser u) {
        return db.sql("""
                SELECT DISTINCT ON (lp.user_id) lp.user_id AS "userId", us.name, us.role, lp.lat, lp.lng, lp.accuracy_m AS accuracy,
                       lp.recorded_at AS "recordedAt", extract(epoch FROM now() - lp.recorded_at)::int AS "ageSeconds",
                       (SELECT count(*) FROM location_pings x WHERE x.user_id = lp.user_id AND x.recorded_at >= date_trunc('day', now())) AS "pointsToday"
                FROM location_pings lp JOIN users us ON us.id = lp.user_id
                WHERE lp.tenant_id = :t AND lp.recorded_at > now() - interval '12 hours'
                ORDER BY lp.user_id, lp.recorded_at DESC
                """).param("t", u.tenantId()).query().listOfRows();
    }

    /** Today's trail for one person, oldest first, thinned to at most 500 points. */
    @GetMapping("/trail/{userId}")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT')")
    public List<Map<String, Object>> trail(@AuthenticationPrincipal AuthUser u, @PathVariable long userId) {
        return db.sql("""
                SELECT lat, lng, recorded_at AS "recordedAt" FROM (
                  SELECT lat, lng, recorded_at, row_number() OVER (ORDER BY recorded_at) rn, count(*) OVER () total
                  FROM location_pings WHERE tenant_id = :t AND user_id = :u AND recorded_at >= date_trunc('day', now())) x
                WHERE total <= 500 OR rn % CEIL(total / 500.0)::int = 0 ORDER BY recorded_at
                """).param("t", u.tenantId()).param("u", userId).query().listOfRows();
    }

    @org.springframework.scheduling.annotation.Scheduled(cron = "0 15 3 * * *")
    public void purgeOldPings() {
        db.sql("DELETE FROM location_pings WHERE recorded_at < now() - interval '30 days'").update();
    }

    public record CheckIn(Long customerId, @DecimalMin("-90") @DecimalMax("90") Double lat,
                          @DecimalMin("-180") @DecimalMax("180") Double lng, @Size(max = 500) String note) {}

    @PostMapping("/visits")
    @PreAuthorize("hasRole('SALES')")
    public Map<String, Object> checkIn(@AuthenticationPrincipal AuthUser u, @Valid @RequestBody CheckIn in) {
        if (in.customerId() != null) {
            int ok = db.sql("SELECT count(*) FROM customers WHERE id = :c AND tenant_id = :t").param("c", in.customerId())
                    .param("t", u.tenantId()).query(Integer.class).single();
            if (ok == 0) throw ApiException.notFound("Customer");
        }
        Long id = db.sql("""
                INSERT INTO visits (tenant_id, user_id, customer_id, lat, lng, note) VALUES (:t, :u, :c, :la, :ln, :n) RETURNING id
                """).param("t", u.tenantId()).param("u", u.userId()).param("c", in.customerId())
                .param("la", in.lat()).param("ln", in.lng()).param("n", in.note()).query(Long.class).single();
        return Map.of("id", id);
    }
}
