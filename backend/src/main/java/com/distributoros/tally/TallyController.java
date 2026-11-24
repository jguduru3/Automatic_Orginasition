package com.distributoros.tally;

import com.distributoros.common.ApiException;
import com.distributoros.config.AppProperties;
import com.distributoros.security.AuthUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/tally")
@PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT')")
public class TallyController {
    private final JdbcClient db;
    private final TallySyncService sync;
    private final AppProperties props;

    public TallyController(JdbcClient db, TallySyncService sync, AppProperties props) {
        this.db = db; this.sync = sync; this.props = props;
    }

    @GetMapping
    public Map<String, Object> status(@AuthenticationPrincipal AuthUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", props.tally().mode());
        m.put("company", props.tally().company());
        m.put("autoSync", props.tally().autoSync());
        m.put("mapping", Map.of("salesLedger", props.tally().salesLedger(), "cgstLedger", props.tally().cgstLedger(),
                "sgstLedger", props.tally().sgstLedger(), "bankLedger", props.tally().bankLedger()));
        m.put("queue", db.sql("""
                SELECT id, voucher_type AS type, ref, party, amount, attempts, last_error AS "lastError", created_at AS "createdAt"
                FROM tally_outbox WHERE tenant_id = :t AND status = 'PENDING' ORDER BY id
                """).param("t", u.tenantId()).query().listOfRows());
        m.put("failed", db.sql("""
                SELECT id, voucher_type AS type, ref, party, amount, attempts, last_error AS "lastError"
                FROM tally_outbox WHERE tenant_id = :t AND status = 'FAILED' ORDER BY id DESC LIMIT 50
                """).param("t", u.tenantId()).query().listOfRows());
        m.put("log", db.sql("""
                SELECT id, voucher_type AS type, ref, party, amount, tally_voucher_no AS "voucherNo", posted_at AS "postedAt"
                FROM tally_outbox WHERE tenant_id = :t AND status = 'POSTED' ORDER BY posted_at DESC LIMIT 50
                """).param("t", u.tenantId()).query().listOfRows());
        m.put("postedToday", db.sql("""
                SELECT count(*) FROM tally_outbox WHERE tenant_id = :t AND status = 'POSTED' AND posted_at >= date_trunc('day', now())
                """).param("t", u.tenantId()).query(Long.class).single());
        return m;
    }

    @PostMapping("/sync")
    public TallySyncService.Result syncNow(@AuthenticationPrincipal AuthUser u) {
        return sync.sync(u.tenantId(), u.userId());
    }

    @PostMapping("/failed/{id}/retry")
    public Map<String, Object> retry(@AuthenticationPrincipal AuthUser u, @PathVariable long id) {
        int n = db.sql("UPDATE tally_outbox SET status = 'PENDING', attempts = 0 WHERE id = :id AND tenant_id = :t AND status = 'FAILED'")
                .param("id", id).param("t", u.tenantId()).update();
        if (n == 0) throw ApiException.notFound("Failed voucher");
        return Map.of("ok", true);
    }
}
