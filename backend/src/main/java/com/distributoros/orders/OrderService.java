package com.distributoros.orders;

import com.distributoros.common.Activity;
import com.distributoros.common.ApiException;
import com.distributoros.common.DocNumbers;
import com.distributoros.common.Money;
import com.distributoros.config.AppProperties;
import com.distributoros.customers.CreditService;
import com.distributoros.customers.PricingService;
import com.distributoros.integrations.OutboundMessage;
import com.distributoros.integrations.TallyXml;
import com.distributoros.security.AuthUser;
import com.distributoros.storage.BlobStorage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.*;

@Service
public class OrderService {
    private static final Locale IN = Locale.forLanguageTag("en-IN");

    private final JdbcClient db;
    private final PricingService pricing;
    private final CreditService credit;
    private final DocNumbers numbers;
    private final Activity activity;
    private final ApplicationEventPublisher events;
    private final BlobStorage blobs;
    private final AppProperties props;

    public OrderService(JdbcClient db, PricingService pricing, CreditService credit, DocNumbers numbers,
                        Activity activity, ApplicationEventPublisher events, BlobStorage blobs, AppProperties props) {
        this.db = db; this.pricing = pricing; this.credit = credit; this.numbers = numbers;
        this.activity = activity; this.events = events; this.blobs = blobs; this.props = props;
    }

    public record LineRequest(long productId, int qty) {}

    public record CreatedOrder(long id, long number, BigDecimal total, List<String> backorders) {}

    static String inr(BigDecimal v) {
        NumberFormat f = NumberFormat.getNumberInstance(IN);
        f.setMaximumFractionDigits(0);
        return "Rs " + f.format(v.setScale(0, RoundingMode.HALF_UP));
    }

    /**
     * Creates a sales order. Reserves free stock line by line (shortfall becomes back-order), then checks credit
     * on the reserved value. Any failure rolls back the reservation. Customer row lock serialises credit checks;
     * stock rows are locked in product-id order to avoid deadlocks.
     */
    @Transactional
    public CreatedOrder create(AuthUser u, long customerId, String source, Long conversationId,
                               List<LineRequest> requested, boolean overrideCredit) {
        if (requested == null || requested.isEmpty()) throw ApiException.badRequest("NO_LINES", "Add at least one item");
        if (requested.size() > 100) throw ApiException.badRequest("TOO_MANY_LINES", "An order can have at most 100 lines");
        if (overrideCredit && !u.isOwner()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the owner can approve a credit override");
        }
        // merge duplicate products, validate quantities
        Map<Long, Integer> qtyByProduct = new TreeMap<>();
        for (LineRequest l : requested) {
            if (l.qty() <= 0 || l.qty() > 100_000) throw ApiException.badRequest("BAD_QTY", "Quantity must be 1 to 100000");
            qtyByProduct.merge(l.productId(), l.qty(), Integer::sum);
        }
        credit.lockCustomer(u.tenantId(), customerId);
        Map<Long, PricingService.Quote> quotes = pricing.quote(u.tenantId(), customerId, qtyByProduct.keySet());
        if (quotes.size() != qtyByProduct.size()) throw ApiException.badRequest("BAD_PRODUCT", "One or more items do not exist");

        record Line(long productId, int qty, int backorder, BigDecimal rate, BigDecimal gst, BigDecimal amount) {}
        List<Line> lines = new ArrayList<>();
        List<String> backorders = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO, tax = BigDecimal.ZERO;
        for (var e : qtyByProduct.entrySet()) {          // TreeMap: ascending product id = consistent lock order
            long pid = e.getKey();
            int want = e.getValue();
            Integer free = db.sql("SELECT on_hand - reserved FROM stock WHERE product_id = :p AND tenant_id = :t FOR UPDATE")
                    .param("p", pid).param("t", u.tenantId()).query(Integer.class).optional().orElse(0);
            int take = Math.max(0, Math.min(want, free));
            if (take > 0) {
                db.sql("UPDATE stock SET reserved = reserved + :q, updated_at = now() WHERE product_id = :p")
                        .param("q", take).param("p", pid).update();
            }
            PricingService.Quote q = quotes.get(pid);
            BigDecimal amount = q.rate().multiply(BigDecimal.valueOf(take));
            subtotal = subtotal.add(amount);
            tax = tax.add(amount.multiply(q.gstRate()).movePointLeft(2));
            if (take < want) backorders.add((want - take) + " x " + q.name());
            lines.add(new Line(pid, want, want - take, q.rate(), q.gstRate(), Money.paise(amount)));
        }
        if (subtotal.signum() == 0) {
            throw ApiException.conflict("OUT_OF_STOCK", "None of these items are in stock");
        }
        tax = Money.paise(tax);
        BigDecimal total = subtotal.add(tax);

        CreditService.Credit c = credit.credit(u.tenantId(), customerId);
        if (c.wouldExceed(total) && !overrideCredit) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CREDIT_HOLD",
                    "Order of " + inr(total) + " takes the customer to " + inr(c.after(total)) + " against a limit of " + inr(c.limit()));
        }

        long number = numbers.next(u.tenantId(), "SO");
        Long sp = db.sql("SELECT salesperson_id FROM customers WHERE id = :c").param("c", customerId)
                .query(Long.class).optional().orElse(null);
        if ("SALES".equals(u.role())) sp = u.userId();
        long orderId = db.sql("""
                INSERT INTO sales_orders (tenant_id, number, customer_id, source, status, conversation_id, salesperson_id,
                                          created_by, subtotal, tax, total, credit_override_by)
                VALUES (:t, :n, :c, :src, 'CONFIRMED', :conv, :sp, :u, :sub, :tax, :tot, :ov) RETURNING id
                """).param("t", u.tenantId()).param("n", number).param("c", customerId).param("src", source)
                .param("conv", conversationId).param("sp", sp).param("u", u.userId())
                .param("sub", subtotal).param("tax", tax).param("tot", total)
                .param("ov", c.wouldExceed(total) ? u.userId() : null)
                .query(Long.class).single();
        for (Line l : lines) {
            db.sql("""
                    INSERT INTO order_lines (order_id, product_id, qty, backorder_qty, rate, gst_rate, amount)
                    VALUES (:o, :p, :q, :b, :r, :g, :a)
                    """).param("o", orderId).param("p", l.productId()).param("q", l.qty()).param("b", l.backorder())
                    .param("r", l.rate()).param("g", l.gst()).param("a", l.amount()).update();
        }
        String customerName = db.sql("SELECT name FROM customers WHERE id = :c").param("c", customerId).query(String.class).single();
        activity.log(u.tenantId(), u.userId(), "ORDER_CONFIRMED",
                "SO-" + number + " confirmed for " + customerName + " (" + inr(total) + ", " + source.toLowerCase() + ")"
                        + (c.wouldExceed(total) ? ", credit override by owner" : ""),
                "order", orderId);
        return new CreatedOrder(orderId, number, total, backorders);
    }

    /** WhatsApp flow: lock the conversation so a double tap cannot create two orders, create, then reply. */
    @Transactional
    public CreatedOrder confirmFromConversation(AuthUser u, long conversationId, List<LineRequest> lines, boolean override) {
        var conv = db.sql("""
                SELECT id, customer_id, phone, status FROM conversations
                WHERE id = :id AND tenant_id = :t FOR UPDATE
                """).param("id", conversationId).param("t", u.tenantId()).query().listOfRows();
        if (conv.isEmpty()) throw ApiException.notFound("Conversation");
        Map<String, Object> cv = conv.get(0);
        if (!"NEW".equals(cv.get("status"))) throw ApiException.conflict("ALREADY_HANDLED", "This message was already converted");
        if (cv.get("customer_id") == null) {
            throw ApiException.badRequest("UNKNOWN_CUSTOMER", "Add this phone number as a customer first");
        }
        long customerId = ((Number) cv.get("customer_id")).longValue();
        CreatedOrder o = create(u, customerId, "WHATSAPP", conversationId, lines, override);
        db.sql("UPDATE conversations SET status = 'CONVERTED', order_id = :o WHERE id = :id")
                .param("o", o.id()).param("id", conversationId).update();

        StringBuilder reply = new StringBuilder("Order SO-").append(o.number()).append(" confirmed.\n");
        db.sql("""
                SELECT l.qty - l.backorder_qty AS q, p.name FROM order_lines l JOIN products p ON p.id = l.product_id
                WHERE l.order_id = :o AND l.qty > l.backorder_qty ORDER BY l.id
                """).param("o", o.id()).query().listOfRows()
                .forEach(r -> reply.append(r.get("q")).append(" x ").append(r.get("name")).append('\n'));
        reply.append("Total ").append(inr(o.total())).append(" incl. GST. Delivery today.");
        if (!o.backorders().isEmpty()) reply.append("\nBack-order: ").append(String.join(", ", o.backorders())).append('.');
        sendMessage(u.tenantId(), conversationId, (String) cv.get("phone"), reply.toString());
        return o;
    }

    /** Stores an outgoing message in the thread and queues the WhatsApp send for after commit. */
    public void sendMessage(long tenantId, long conversationId, String phone, String body) {
        sendMessage(tenantId, conversationId, phone, body, null, null);
    }

    public void sendMessage(long tenantId, long conversationId, String phone, String body, String template, List<String> params) {
        long msgId = db.sql("""
                INSERT INTO messages (tenant_id, conversation_id, direction, body) VALUES (:t, :c, 'OUT', :b) RETURNING id
                """).param("t", tenantId).param("c", conversationId).param("b", body).query(Long.class).single();
        db.sql("UPDATE conversations SET last_message_at = now() WHERE id = :c").param("c", conversationId).update();
        events.publishEvent(new OutboundMessage(tenantId, msgId, phone, body, template, params));
    }

    private Map<String, Object> lockOrder(AuthUser u, long id) {
        var rows = db.sql("""
                SELECT o.id, o.number, o.status, o.customer_id, o.subtotal, o.tax, o.total, c.name AS customer
                FROM sales_orders o JOIN customers c ON c.id = o.customer_id
                WHERE o.id = :id AND o.tenant_id = :t FOR UPDATE OF o
                """).param("id", id).param("t", u.tenantId()).query().listOfRows();
        if (rows.isEmpty()) throw ApiException.notFound("Order");
        return rows.get(0);
    }

    private static ApiException wrongState(Map<String, Object> o, String action) {
        return ApiException.conflict("BAD_STATE", "SO-" + o.get("number") + " is " + o.get("status") + " and cannot be " + action);
    }

    @Transactional
    public void pack(AuthUser u, long id) {
        Map<String, Object> o = lockOrder(u, id);
        if (!"CONFIRMED".equals(o.get("status"))) throw wrongState(o, "packed");
        db.sql("UPDATE sales_orders SET status = 'PACKED', packed_at = now() WHERE id = :id").param("id", id).update();
        activity.log(u.tenantId(), u.userId(), "ORDER_PACKED", "SO-" + o.get("number") + " packed", "order", id);
    }

    /** Dispatch: stock leaves the godown, invoice is raised and a Sales voucher is queued for Tally (same transaction). */
    @Transactional
    public Map<String, Object> dispatch(AuthUser u, long id, long vehicleId) {
        Map<String, Object> o = lockOrder(u, id);
        if (!"PACKED".equals(o.get("status"))) throw wrongState(o, "dispatched");
        var veh = db.sql("SELECT reg_no, driver_name FROM vehicles WHERE id = :v AND tenant_id = :t")
                .param("v", vehicleId).param("t", u.tenantId()).query().listOfRows();
        if (veh.isEmpty()) throw ApiException.badRequest("BAD_VEHICLE", "Vehicle not found");

        var lines = db.sql("""
                SELECT product_id, qty - backorder_qty AS q FROM order_lines WHERE order_id = :o AND qty > backorder_qty
                ORDER BY product_id
                """).param("o", id).query().listOfRows();
        for (var l : lines) {
            int n = db.sql("""
                    UPDATE stock SET on_hand = on_hand - :q, reserved = reserved - :q, updated_at = now()
                    WHERE product_id = :p AND reserved >= :q AND on_hand >= :q
                    """).param("q", ((Number) l.get("q")).intValue()).param("p", ((Number) l.get("product_id")).longValue()).update();
            if (n == 0) throw ApiException.conflict("STOCK_MISMATCH", "Reserved stock no longer matches this order");
        }
        long invNo = numbers.next(u.tenantId(), "INV");
        BigDecimal total = Money.of(o.get("total"));
        long customerId = ((Number) o.get("customer_id")).longValue();
        db.sql("""
                INSERT INTO invoices (tenant_id, number, customer_id, order_id, amount) VALUES (:t, :n, :c, :o, :a)
                """).param("t", u.tenantId()).param("n", invNo).param("c", customerId).param("o", id).param("a", total).update();
        String invRef = "INV-" + invNo;
        String party = (String) o.get("customer");
        AppProperties.Tally tc = props.tally();
        String xml = TallyXml.sales(tc.company(), LocalDate.now(), invRef, party, tc.salesLedger(), tc.cgstLedger(),
                tc.sgstLedger(), Money.of(o.get("subtotal")), Money.of(o.get("tax")));
        db.sql("""
                INSERT INTO tally_outbox (tenant_id, voucher_type, ref, party, amount, payload)
                VALUES (:t, 'SALES', :r, :p, :a, :x)
                """).param("t", u.tenantId()).param("r", invRef).param("p", party).param("a", total).param("x", xml).update();
        db.sql("UPDATE sales_orders SET status = 'DISPATCHED', dispatched_at = now(), vehicle_id = :v WHERE id = :id")
                .param("v", vehicleId).param("id", id).update();
        Map<String, Object> v = veh.get(0);
        activity.log(u.tenantId(), u.userId(), "ORDER_DISPATCHED",
                "SO-" + o.get("number") + " dispatched on " + v.get("reg_no") + ", " + invRef + " raised", "order", id);
        return Map.of("invoice", invRef, "vehicle", v.get("reg_no"), "driver", v.get("driver_name"));
    }

    @Transactional
    public void deliver(AuthUser u, long id, byte[] pod, String contentType) {
        Map<String, Object> o = lockOrder(u, id);
        if (!"DISPATCHED".equals(o.get("status"))) throw wrongState(o, "delivered");
        String path = null;
        if (pod != null && pod.length > 0) {
            String ext = "image/png".equals(contentType) ? "png" : "jpg";
            path = "tenants/" + u.tenantId() + "/pod/SO-" + o.get("number") + "-" + UUID.randomUUID() + "." + ext;
            blobs.put(path, pod, contentType);
        }
        db.sql("UPDATE sales_orders SET status = 'DELIVERED', delivered_at = now(), pod_blob = :p WHERE id = :id")
                .param("p", path).param("id", id).update();
        activity.log(u.tenantId(), u.userId(), "ORDER_DELIVERED",
                "SO-" + o.get("number") + " delivered to " + o.get("customer") + (path != null ? ", photo POD saved" : ""), "order", id);
    }

    @Transactional
    public void cancel(AuthUser u, long id) {
        Map<String, Object> o = lockOrder(u, id);
        String st = (String) o.get("status");
        if (!"CONFIRMED".equals(st) && !"PACKED".equals(st)) throw wrongState(o, "cancelled");
        var lines = db.sql("""
                SELECT product_id, qty - backorder_qty AS q FROM order_lines WHERE order_id = :o AND qty > backorder_qty
                ORDER BY product_id
                """).param("o", id).query().listOfRows();
        for (var l : lines) {
            db.sql("UPDATE stock SET reserved = reserved - :q, updated_at = now() WHERE product_id = :p AND reserved >= :q")
                    .param("q", ((Number) l.get("q")).intValue()).param("p", ((Number) l.get("product_id")).longValue()).update();
        }
        db.sql("UPDATE sales_orders SET status = 'CANCELLED' WHERE id = :id").param("id", id).update();
        activity.log(u.tenantId(), u.userId(), "ORDER_CANCELLED", "SO-" + o.get("number") + " cancelled, stock released", "order", id);
    }
}
