package com.distributoros.orders;

import com.distributoros.common.ApiException;
import com.distributoros.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private static final Set<String> STATUSES = Set.of("CONFIRMED", "PACKED", "DISPATCHED", "DELIVERED", "CANCELLED");
    private final JdbcClient db;
    private final OrderService orders;

    public OrderController(JdbcClient db, OrderService orders) { this.db = db; this.orders = orders; }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthUser u,
                                          @RequestParam(defaultValue = "OPEN") String status,
                                          @RequestParam(defaultValue = "100") int limit) {
        String st = status.toUpperCase(Locale.ROOT);
        String filter;
        if ("OPEN".equals(st)) filter = " AND o.status IN ('CONFIRMED','PACKED','DISPATCHED')";
        else if ("ALL".equals(st)) filter = "";
        else if (STATUSES.contains(st)) filter = " AND o.status = :st";
        else throw ApiException.badRequest("BAD_STATUS", "Unknown status " + status);
        if ("DELIVERY".equals(u.role())) filter += " AND o.status IN ('PACKED','DISPATCHED','DELIVERED')";
        var stmt = db.sql("""
                SELECT o.id, o.number, o.status, o.source, o.subtotal, o.tax, o.total, o.created_at AS "createdAt",
                       o.dispatched_at AS "dispatchedAt", o.delivered_at AS "deliveredAt", o.pod_blob AS "podPath",
                       c.id AS "customerId", c.name AS customer, c.town,
                       v.id AS "vehicleId", v.reg_no AS vehicle, v.driver_name AS driver,
                       'INV-' || i.number AS invoice,
                       (SELECT string_agg((l.qty - l.backorder_qty) || 'x ' || p.sku, ', ' ORDER BY l.id)
                          FROM order_lines l JOIN products p ON p.id = l.product_id WHERE l.order_id = o.id) AS summary,
                       (SELECT COALESCE(sum(l.backorder_qty), 0) FROM order_lines l WHERE l.order_id = o.id) AS backorder
                FROM sales_orders o
                JOIN customers c ON c.id = o.customer_id
                LEFT JOIN vehicles v ON v.id = o.vehicle_id
                LEFT JOIN invoices i ON i.order_id = o.id
                WHERE o.tenant_id = :t""" + filter + " ORDER BY o.number DESC LIMIT :lim")
                .param("t", u.tenantId()).param("lim", Math.max(1, Math.min(limit, 500)));
        if (filter.contains(":st")) stmt = stmt.param("st", st);
        return stmt.query().listOfRows();
    }

    @GetMapping("/summary")
    public List<Map<String, Object>> summary(@AuthenticationPrincipal AuthUser u) {
        return db.sql("""
                SELECT s.status, COALESCE(count(o.id), 0) AS count, COALESCE(sum(o.total), 0) AS value
                FROM (VALUES ('CONFIRMED',1),('PACKED',2),('DISPATCHED',3),('DELIVERED',4)) s(status, ord)
                LEFT JOIN sales_orders o ON o.status = s.status AND o.tenant_id = :t
                     AND (s.status <> 'DELIVERED' OR o.delivered_at >= date_trunc('day', now()))
                GROUP BY s.status, s.ord ORDER BY s.ord
                """).param("t", u.tenantId()).query().listOfRows();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal AuthUser u, @PathVariable long id) {
        var rows = db.sql("""
                SELECT o.id, o.number, o.status, o.source, o.subtotal, o.tax, o.total, o.created_at AS "createdAt",
                       c.name AS customer, o.pod_blob AS "podPath"
                FROM sales_orders o JOIN customers c ON c.id = o.customer_id WHERE o.id = :id AND o.tenant_id = :t
                """).param("id", id).param("t", u.tenantId()).query().listOfRows();
        if (rows.isEmpty()) throw ApiException.notFound("Order");
        Map<String, Object> o = new LinkedHashMap<>(rows.get(0));
        o.put("lines", db.sql("""
                SELECT l.product_id AS "productId", p.sku, p.name, l.qty, l.backorder_qty AS backorder, l.rate, l.amount
                FROM order_lines l JOIN products p ON p.id = l.product_id WHERE l.order_id = :o ORDER BY l.id
                """).param("o", id).query().listOfRows());
        return o;
    }

    public record Line(@NotNull Long productId, @NotNull @Min(1) @Max(100000) Integer qty) {}

    public record NewOrder(@NotNull Long customerId,
                           @NotBlank @Pattern(regexp = "SALESPERSON|PHONE") String source,
                           @NotEmpty @Size(max = 100) List<@Valid Line> lines,
                           boolean overrideCredit) {}

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT','SALES')")
    public OrderService.CreatedOrder create(@AuthenticationPrincipal AuthUser u, @Valid @RequestBody NewOrder in) {
        return orders.create(u, in.customerId(), in.source(), null,
                in.lines().stream().map(l -> new OrderService.LineRequest(l.productId(), l.qty())).toList(),
                in.overrideCredit());
    }

    @PostMapping("/{id}/pack")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT','DELIVERY')")
    public Map<String, Object> pack(@AuthenticationPrincipal AuthUser u, @PathVariable long id) {
        orders.pack(u, id);
        return Map.of("ok", true);
    }

    public record Dispatch(@NotNull Long vehicleId) {}

    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT','DELIVERY')")
    public Map<String, Object> dispatch(@AuthenticationPrincipal AuthUser u, @PathVariable long id,
                                        @Valid @RequestBody Dispatch in) {
        return orders.dispatch(u, id, in.vehicleId());
    }

    @PostMapping(value = "/{id}/deliver")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT','DELIVERY')")
    public Map<String, Object> deliver(@AuthenticationPrincipal AuthUser u, @PathVariable long id,
                                       @RequestParam(value = "pod", required = false) MultipartFile pod) throws IOException {
        byte[] data = null;
        String type = null;
        if (pod != null && !pod.isEmpty()) {
            type = pod.getContentType();
            if (!"image/jpeg".equals(type) && !"image/png".equals(type)) {
                throw ApiException.badRequest("BAD_FILE", "Proof of delivery must be a JPEG or PNG photo");
            }
            data = pod.getBytes();
            if (!looksLikeImage(data)) throw ApiException.badRequest("BAD_FILE", "File is not a valid image");
        }
        orders.deliver(u, id, data, type);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT')")
    public Map<String, Object> cancel(@AuthenticationPrincipal AuthUser u, @PathVariable long id) {
        orders.cancel(u, id);
        return Map.of("ok", true);
    }

    static boolean looksLikeImage(byte[] d) {
        if (d.length < 4) return false;
        boolean jpeg = (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8;
        boolean png = (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
        return jpeg || png;
    }

    @GetMapping("/vehicles")
    public List<Map<String, Object>> vehicles(@AuthenticationPrincipal AuthUser u) {
        return db.sql("SELECT id, reg_no AS \"regNo\", type, driver_name AS driver FROM vehicles WHERE tenant_id = :t ORDER BY id")
                .param("t", u.tenantId()).query().listOfRows();
    }
}
