package com.distributoros.purchases;

import com.distributoros.catalog.StockService;
import com.distributoros.common.Activity;
import com.distributoros.common.ApiException;
import com.distributoros.common.DocNumbers;
import com.distributoros.common.Money;
import com.distributoros.config.AppProperties;
import com.distributoros.integrations.OutboundMessage;
import com.distributoros.integrations.TallyPurchaseXml;
import com.distributoros.security.AuthUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class PurchaseService {
    private final JdbcClient db;
    private final DocNumbers numbers;
    private final StockService stock;
    private final Activity activity;
    private final AppProperties props;
    private final ApplicationEventPublisher events;

    public PurchaseService(JdbcClient db, DocNumbers numbers, StockService stock, Activity activity, AppProperties props,
                           ApplicationEventPublisher events) {
        this.db = db; this.numbers = numbers; this.stock = stock; this.activity = activity; this.props = props; this.events = events;
    }

    public record Line(long productId, int qty, BigDecimal rate) {}

    @Transactional
    public Map<String, Object> create(AuthUser u, long supplierId, Long godownId, List<Line> lines, LocalDate expected, String note) {
        if (lines == null || lines.isEmpty()) throw ApiException.badRequest("NO_LINES", "Add at least one item");
        Integer sup = db.sql("SELECT count(*) FROM suppliers WHERE id = :s AND tenant_id = :t AND active").param("s", supplierId)
                .param("t", u.tenantId()).query(Integer.class).single();
        if (sup == 0) throw ApiException.badRequest("BAD_SUPPLIER", "Supplier not found");
        long g = stock.godown(u.tenantId(), godownId);
        long number = numbers.next(u.tenantId(), "PO");
        long poId = db.sql("""
                INSERT INTO purchase_orders (tenant_id, number, supplier_id, godown_id, status, expected_date, note, created_by)
                VALUES (:t, :n, :s, :g, 'DRAFT', :e, :note, :u) RETURNING id
                """).param("t", u.tenantId()).param("n", number).param("s", supplierId).param("g", g)
                .param("e", expected).param("note", note).param("u", u.userId()).query(Long.class).single();
        BigDecimal sub = BigDecimal.ZERO, tax = BigDecimal.ZERO;
        for (Line l : lines) {
            if (l.qty() <= 0 || l.qty() > 1_000_000) throw ApiException.badRequest("BAD_QTY", "Quantity must be 1 to 1000000");
            if (l.rate() == null || l.rate().signum() < 0) throw ApiException.badRequest("BAD_RATE", "Rate must be zero or more");
            var gst = db.sql("SELECT gst_rate FROM products WHERE id = :p AND tenant_id = :t").param("p", l.productId())
                    .param("t", u.tenantId()).query(BigDecimal.class).optional()
                    .orElseThrow(() -> ApiException.badRequest("BAD_PRODUCT", "Item not found"));
            db.sql("INSERT INTO po_lines (po_id, product_id, qty, rate, gst_rate) VALUES (:po, :p, :q, :r, :g)")
                    .param("po", poId).param("p", l.productId()).param("q", l.qty()).param("r", Money.paise(l.rate())).param("g", gst).update();
            BigDecimal amt = l.rate().multiply(BigDecimal.valueOf(l.qty()));
            sub = sub.add(amt);
            tax = tax.add(amt.multiply(gst).movePointLeft(2));
        }
        sub = Money.paise(sub);
        tax = Money.paise(tax);
        db.sql("UPDATE purchase_orders SET subtotal = :s, tax = :x, total = :tot WHERE id = :id")
                .param("s", sub).param("x", tax).param("tot", sub.add(tax)).param("id", poId).update();
        activity.log(u.tenantId(), u.userId(), "PO_CREATED", "PO-" + number + " drafted", "purchase", poId);
        return Map.of("id", poId, "number", number, "total", sub.add(tax));
    }

    private Map<String, Object> lock(AuthUser u, long id) {
        var rows = db.sql("""
                SELECT po.id, po.number, po.status, po.total, po.subtotal, po.tax, po.godown_id, po.expected_date,
                       s.name AS supplier, s.phone AS supplier_phone
                FROM purchase_orders po JOIN suppliers s ON s.id = po.supplier_id
                WHERE po.id = :id AND po.tenant_id = :t FOR UPDATE OF po
                """).param("id", id).param("t", u.tenantId()).query().listOfRows();
        if (rows.isEmpty()) throw ApiException.notFound("Purchase order");
        return rows.get(0);
    }

    /** Marks the PO sent and WhatsApps it to the supplier (template if configured). */
    @Transactional
    public Map<String, Object> send(AuthUser u, long id) {
        var po = lock(u, id);
        if (!"DRAFT".equals(po.get("status"))) throw ApiException.conflict("BAD_STATE", "Only draft orders can be sent");
        db.sql("UPDATE purchase_orders SET status = 'SENT', sent_at = now() WHERE id = :id").param("id", id).update();
        String phone = (String) po.get("supplier_phone");
        boolean messaged = false;
        if (phone != null && !phone.isBlank()) {
            String tenant = db.sql("SELECT name FROM tenants WHERE id = :t").param("t", u.tenantId()).query(String.class).single();
            StringBuilder body = new StringBuilder("Purchase order PO-" + po.get("number") + " from " + tenant + "\n");
            db.sql("SELECT l.qty, p.name, l.rate FROM po_lines l JOIN products p ON p.id = l.product_id WHERE l.po_id = :po ORDER BY l.id")
                    .param("po", id).query().listOfRows()
                    .forEach(r -> body.append(r.get("qty")).append(" x ").append(r.get("name")).append(" @ Rs ").append(r.get("rate")).append('\n'));
            body.append("Total Rs ").append(Money.of(po.get("total")).toPlainString());
            if (po.get("expected_date") != null) body.append("\nPlease deliver by ").append(po.get("expected_date"));
            String tpl = props.whatsapp().poTemplate();
            events.publishEvent(new OutboundMessage(u.tenantId(), 0, phone, body.toString(),
                    tpl == null || tpl.isBlank() ? null : tpl,
                    List.of(String.valueOf(po.get("supplier")), "PO-" + po.get("number"), "Rs " + Money.of(po.get("total")).toPlainString(),
                            String.valueOf(po.get("expected_date")))));
            messaged = true;
        }
        activity.log(u.tenantId(), u.userId(), "PO_SENT", "PO-" + po.get("number") + " sent to " + po.get("supplier"), "purchase", id);
        return Map.of("ok", true, "messaged", messaged);
    }

    public record Receipt(long poLineId, int qty, String batchNo, LocalDate expiryDate, LocalDate mfgDate, BigDecimal mrp) {}

    /** Goods receipt: stock goes up (with batches where tracked), PO moves to PARTIAL/RECEIVED, Purchase voucher queued. */
    @Transactional
    public Map<String, Object> receive(AuthUser u, long id, String supplierInvoiceNo, List<Receipt> lines) {
        var po = lock(u, id);
        String st = (String) po.get("status");
        if (!"SENT".equals(st) && !"PARTIAL".equals(st)) throw ApiException.conflict("BAD_STATE", "Send the order before receiving goods");
        if (lines == null || lines.isEmpty()) throw ApiException.badRequest("NO_LINES", "Enter what was received");
        long g = ((Number) po.get("godown_id")).longValue();
        long grnNo = numbers.next(u.tenantId(), "GRN");
        long grnId = db.sql("""
                INSERT INTO goods_receipts (tenant_id, number, po_id, godown_id, supplier_invoice_no, amount, created_by)
                VALUES (:t, :n, :po, :g, :inv, 0, :u) RETURNING id
                """).param("t", u.tenantId()).param("n", grnNo).param("po", id).param("g", g).param("inv", supplierInvoiceNo)
                .param("u", u.userId()).query(Long.class).single();
        BigDecimal taxable = BigDecimal.ZERO, tax = BigDecimal.ZERO;
        List<Receipt> sorted = lines.stream().filter(r -> r.qty() > 0).sorted(Comparator.comparingLong(Receipt::poLineId)).toList();
        if (sorted.isEmpty()) throw ApiException.badRequest("NO_LINES", "Enter what was received");
        for (Receipt r : sorted) {
            var pl = db.sql("""
                    SELECT product_id, qty - received_qty AS open, rate, gst_rate FROM po_lines WHERE id = :l AND po_id = :po FOR UPDATE
                    """).param("l", r.poLineId()).param("po", id).query().listOfRows();
            if (pl.isEmpty()) throw ApiException.badRequest("BAD_LINE", "Line not on this purchase order");
            int open = ((Number) pl.get(0).get("open")).intValue();
            if (r.qty() > open) throw ApiException.badRequest("OVER_RECEIPT", "Received more than ordered (open " + open + ")");
            long pid = ((Number) pl.get(0).get("product_id")).longValue();
            Long batchId = stock.receive(u.tenantId(), pid, g, r.qty(), r.batchNo(), r.expiryDate(), r.mfgDate(), r.mrp());
            db.sql("UPDATE po_lines SET received_qty = received_qty + :q WHERE id = :l").param("q", r.qty()).param("l", r.poLineId()).update();
            db.sql("INSERT INTO grn_lines (grn_id, po_line_id, product_id, qty, batch_id) VALUES (:g, :l, :p, :q, :b)")
                    .param("g", grnId).param("l", r.poLineId()).param("p", pid).param("q", r.qty()).param("b", batchId).update();
            BigDecimal amt = Money.of(pl.get(0).get("rate")).multiply(BigDecimal.valueOf(r.qty()));
            taxable = taxable.add(amt);
            tax = tax.add(amt.multiply(Money.of(pl.get(0).get("gst_rate"))).movePointLeft(2));
        }
        taxable = Money.paise(taxable);
        tax = Money.paise(tax);
        db.sql("UPDATE goods_receipts SET amount = :a WHERE id = :id").param("a", taxable.add(tax)).param("id", grnId).update();
        Integer stillOpen = db.sql("SELECT count(*) FROM po_lines WHERE po_id = :po AND received_qty < qty").param("po", id)
                .query(Integer.class).single();
        String newStatus = stillOpen == 0 ? "RECEIVED" : "PARTIAL";
        db.sql("UPDATE purchase_orders SET status = :s WHERE id = :id").param("s", newStatus).param("id", id).update();
        String ref = "GRN-" + grnNo;
        AppProperties.Tally tc = props.tally();
        db.sql("""
                INSERT INTO tally_outbox (tenant_id, voucher_type, ref, party, amount, payload)
                VALUES (:t, 'PURCHASE', :r, :p, :a, :x)
                """).param("t", u.tenantId()).param("r", ref).param("p", po.get("supplier")).param("a", taxable.add(tax))
                .param("x", TallyPurchaseXml.purchase(tc.company(), LocalDate.now(), ref,
                        supplierInvoiceNo == null ? "" : supplierInvoiceNo, (String) po.get("supplier"),
                        tc.purchaseLedger(), tc.inputCgstLedger(), tc.inputSgstLedger(), taxable, tax)).update();
        activity.log(u.tenantId(), u.userId(), "GOODS_RECEIVED",
                ref + " against PO-" + po.get("number") + " (" + sorted.size() + " lines), " + newStatus.toLowerCase(), "purchase", id);
        return Map.of("grn", ref, "status", newStatus);
    }

    @Transactional
    public void cancel(AuthUser u, long id) {
        var po = lock(u, id);
        String st = (String) po.get("status");
        if (!"DRAFT".equals(st) && !"SENT".equals(st)) throw ApiException.conflict("BAD_STATE", "Only orders with nothing received can be cancelled");
        db.sql("UPDATE purchase_orders SET status = 'CANCELLED' WHERE id = :id").param("id", id).update();
        activity.log(u.tenantId(), u.userId(), "PO_CANCELLED", "PO-" + po.get("number") + " cancelled", "purchase", id);
    }
}
