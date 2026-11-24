package com.distributoros.inbox;

import com.distributoros.common.Activity;
import com.distributoros.common.ApiException;
import com.distributoros.customers.CreditService;
import com.distributoros.customers.PricingService;
import com.distributoros.security.AuthUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.SQLException;
import java.util.*;

@Service
public class InboxService {
    private final JdbcClient db;
    private final PricingService pricing;
    private final CreditService credit;
    private final Activity activity;
    private final com.distributoros.catalog.StockService stock;

    public InboxService(JdbcClient db, PricingService pricing, CreditService credit, Activity activity,
                        com.distributoros.catalog.StockService stock) {
        this.db = db; this.pricing = pricing; this.credit = credit; this.activity = activity; this.stock = stock;
    }

    public record Inbound(String waMessageId, String fromPhone, String body, String kind, String mediaBlob, String mediaText) {
        public Inbound(String waMessageId, String fromPhone, String body, String kind, String mediaBlob) {
            this(waMessageId, fromPhone, body, kind, mediaBlob, null);
        }
    }

    /** Message text for a voice note or photo: the machine-read text when available, else a prompt to open it. */
    public static String mediaBody(String kind, String mediaText, String caption) {
        String label = "VOICE".equals(kind) ? "[Voice note]" : "[Photo]";
        StringBuilder b = new StringBuilder(label);
        if (caption != null && !caption.isBlank()) b.append(' ').append(caption.trim());
        if (mediaText != null && !mediaText.isBlank()) b.append('\n').append(mediaText.trim());
        else b.append("VOICE".equals(kind) ? " received - open to listen" : " received - open to view");
        return b.toString();
    }

    /** Stores an inbound message idempotently (WhatsApp retries webhooks) and reopens the conversation. */
    @Transactional
    public boolean receive(long tenantId, Inbound m) {
        String phone = m.fromPhone().replaceAll("\\D", "");
        if (phone.length() < 10 || phone.length() > 15) throw ApiException.badRequest("BAD_PHONE", "Invalid phone number");
        if (phone.length() == 10) phone = "91" + phone;
        String body = m.body() == null ? "" : (m.body().length() > 4000 ? m.body().substring(0, 4000) : m.body());
        Long customerId = db.sql("SELECT id FROM customers WHERE tenant_id = :t AND phone = :p AND active")
                .param("t", tenantId).param("p", phone).query(Long.class).optional().orElse(null);
        long convId = db.sql("""
                INSERT INTO conversations (tenant_id, customer_id, phone, status, last_message_at)
                VALUES (:t, :c, :p, 'NEW', now())
                ON CONFLICT (tenant_id, phone) DO UPDATE SET last_message_at = now(),
                     customer_id = COALESCE(conversations.customer_id, EXCLUDED.customer_id)
                RETURNING id
                """).param("t", tenantId).param("c", customerId).param("p", phone).query(Long.class).single();
        int inserted = db.sql("""
                INSERT INTO messages (tenant_id, conversation_id, direction, body, kind, media_blob, wa_message_id, media_text, media_status)
                VALUES (:t, :c, 'IN', :b, :k, :mb, :w, :mt, :ms)
                ON CONFLICT (tenant_id, wa_message_id) WHERE wa_message_id IS NOT NULL DO NOTHING
                """).param("t", tenantId).param("c", convId).param("b", body).param("k", m.kind())
                .param("mb", m.mediaBlob()).param("w", m.waMessageId()).param("mt", m.mediaText())
                .param("ms", m.mediaBlob() == null ? null : (m.mediaText() != null ? "DONE" : "SKIPPED")).update();
        if (inserted == 0) return false; // duplicate delivery
        int reopened = db.sql("""
                UPDATE conversations SET status = 'NEW', order_id = NULL WHERE id = :c AND status <> 'NEW'
                """).param("c", convId).update();
        if (reopened > 0 || customerId == null) {
            activity.log(tenantId, null, "WHATSAPP_IN", "New WhatsApp message from " + phone, "conversation", convId);
        }
        return true;
    }

    public List<Map<String, Object>> list(AuthUser u) {
        String spFilter = "SALES".equals(u.role()) ? " AND (c.salesperson_id = :uid OR c.id IS NULL)" : "";
        var stmt = db.sql("""
                SELECT cv.id, cv.phone, cv.status, cv.last_message_at AS "lastMessageAt", o.number AS "orderNumber",
                       c.id AS "customerId", c.name AS customer, c.town,
                       (SELECT body FROM messages m WHERE m.conversation_id = cv.id ORDER BY m.id DESC LIMIT 1) AS preview,
                       (SELECT kind FROM messages m WHERE m.conversation_id = cv.id ORDER BY m.id DESC LIMIT 1) AS kind
                FROM conversations cv
                LEFT JOIN customers c ON c.id = cv.customer_id
                LEFT JOIN sales_orders o ON o.id = cv.order_id
                WHERE cv.tenant_id = :t""" + spFilter + """

                ORDER BY (cv.status = 'NEW') DESC, cv.last_message_at DESC LIMIT 200
                """).param("t", u.tenantId());
        if (!spFilter.isEmpty()) stmt = stmt.param("uid", u.userId());
        return stmt.query().listOfRows();
    }

    public Map<String, Object> get(AuthUser u, long id) {
        var rows = db.sql("""
                SELECT cv.id, cv.phone, cv.status, cv.order_id AS "orderId", o.number AS "orderNumber", o.status AS "orderStatus",
                       o.total AS "orderTotal", c.id AS "customerId", c.name AS customer, c.town, pt.name AS tier
                FROM conversations cv
                LEFT JOIN customers c ON c.id = cv.customer_id
                LEFT JOIN price_tiers pt ON pt.id = c.tier_id
                LEFT JOIN sales_orders o ON o.id = cv.order_id
                WHERE cv.id = :id AND cv.tenant_id = :t
                """).param("id", id).param("t", u.tenantId()).query().listOfRows();
        if (rows.isEmpty()) throw ApiException.notFound("Conversation");
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        out.put("messages", db.sql("""
                SELECT id, direction, body, kind, media_blob AS "mediaPath", media_status AS "mediaStatus", created_at AS "createdAt"
                FROM messages WHERE conversation_id = :c ORDER BY id DESC LIMIT 200
                """).param("c", id).query().listOfRows().reversed());
        return out;
    }

    OrderParser parserFor(long tenantId) {
        List<OrderParser.ProductPatterns> pats = db.sql("""
                SELECT id, match_patterns FROM products WHERE tenant_id = :t AND active ORDER BY id
                """).param("t", tenantId).query((rs, i) -> {
                    Array arr = rs.getArray("match_patterns");
                    List<String> list = new ArrayList<>();
                    if (arr != null) {
                        try {
                            for (Object o : (Object[]) arr.getArray()) if (o != null) list.add(o.toString());
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return OrderParser.ProductPatterns.of(rs.getLong("id"), list);
                }).list();
        return new OrderParser(pats);
    }

    /** Builds an editable draft: parsed lines priced for this customer, stock availability and credit position. */
    public Map<String, Object> draft(AuthUser u, long conversationId, String overrideText, Long godownIdOrNull) {
        Map<String, Object> conv = get(u, conversationId);
        String text = overrideText;
        if (text == null) {
            // only messages since the last outgoing reply form the current order
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> msgs = (List<Map<String, Object>>) conv.get("messages");
            StringBuilder sb = new StringBuilder();
            for (int i = msgs.size() - 1; i >= 0; i--) {
                if ("OUT".equals(msgs.get(i).get("direction"))) break;
                sb.insert(0, msgs.get(i).get("body") + "\n");
            }
            text = sb.toString();
        }
        List<OrderParser.ParsedLine> parsed = parserFor(u.tenantId()).parse(text);
        Long customerId = conv.get("customerId") == null ? null : ((Number) conv.get("customerId")).longValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", conversationId);
        out.put("customerId", customerId);
        if (customerId == null) {
            out.put("lines", parsed.stream().map(p -> Map.of("raw", p.raw(), "qty", p.qty())).toList());
            out.put("unknownCustomer", true);
            return out;
        }
        Set<Long> ids = new HashSet<>();
        parsed.forEach(p -> { if (p.productId() != null) ids.add(p.productId()); });
        long godownId = stock.godown(u.tenantId(), godownIdOrNull);
        out.put("godownId", godownId);
        Map<Long, PricingService.Quote> quotes = pricing.quote(u.tenantId(), customerId, godownId, ids);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (OrderParser.ParsedLine p : parsed) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("raw", p.raw());
            l.put("qty", p.qty());
            PricingService.Quote q = p.productId() == null ? null : quotes.get(p.productId());
            if (q != null) {
                l.put("productId", q.productId());
                l.put("sku", q.sku());
                l.put("name", q.name());
                l.put("rate", q.rate());
                l.put("gstRate", q.gstRate());
                l.put("priceSource", q.source());
                l.put("free", q.free());
                l.put("requiresLicence", q.requiresLicence());
            }
            lines.add(l);
        }
        out.put("lines", lines);
        CreditService.Credit c = credit.credit(u.tenantId(), customerId);
        Map<String, Object> cr = new LinkedHashMap<>();
        cr.put("limit", c.limit());
        cr.put("outstanding", c.outstanding());
        cr.put("openOrders", c.openOrders());
        cr.put("oldestDays", c.oldestDays());
        out.put("credit", cr);
        var lic = db.sql("SELECT drug_licence_no, drug_licence_expiry FROM customers WHERE id = :c").param("c", customerId).query().listOfRows().get(0);
        Map<String, Object> licence = new LinkedHashMap<>();
        licence.put("number", lic.get("drug_licence_no"));
        licence.put("expiry", lic.get("drug_licence_expiry"));
        out.put("licence", licence);
        return out;
    }

}
