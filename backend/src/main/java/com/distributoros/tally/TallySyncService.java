package com.distributoros.tally;

import com.distributoros.common.Activity;
import com.distributoros.common.DocNumbers;
import com.distributoros.config.AppProperties;
import com.distributoros.integrations.TallyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

/**
 * Posts queued vouchers to Tally. Rows are claimed with FOR UPDATE SKIP LOCKED, so a manual sync and the
 * scheduled worker (or two replicas) never post the same voucher twice.
 */
@Service
public class TallySyncService {
    private static final Logger log = LoggerFactory.getLogger(TallySyncService.class);
    private final JdbcClient db;
    private final TallyClient tally;
    private final DocNumbers numbers;
    private final Activity activity;
    private final AppProperties props;
    private final TransactionTemplate tx;

    public TallySyncService(JdbcClient db, TallyClient tally, DocNumbers numbers, Activity activity, AppProperties props,
                            TransactionTemplate tx) {
        this.db = db; this.tally = tally; this.numbers = numbers; this.activity = activity; this.props = props; this.tx = tx;
    }

    public record Result(int posted, int failed) {}

    @Transactional
    public Result sync(long tenantId, Long userId) {
        var batch = db.sql("""
                SELECT id, voucher_type, ref, payload, attempts FROM tally_outbox
                WHERE tenant_id = :t AND status = 'PENDING' ORDER BY id LIMIT 50 FOR UPDATE SKIP LOCKED
                """).param("t", tenantId).query().listOfRows();
        int posted = 0, failed = 0;
        for (Map<String, Object> row : batch) {
            long id = ((Number) row.get("id")).longValue();
            String prefix = "SALES".equals(row.get("voucher_type")) ? "SL/" : "RC/";
            try {
                String fallback = prefix + numbers.next(tenantId, "VCH");
                String vch = tally.post((String) row.get("payload"), fallback);
                db.sql("UPDATE tally_outbox SET status = 'POSTED', tally_voucher_no = :v, posted_at = now(), attempts = attempts + 1, last_error = NULL WHERE id = :id")
                        .param("v", vch).param("id", id).update();
                posted++;
            } catch (Exception e) {
                int attempts = ((Number) row.get("attempts")).intValue() + 1;
                String status = attempts >= props.tally().maxAttempts() ? "FAILED" : "PENDING";
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                db.sql("UPDATE tally_outbox SET attempts = :a, status = :s, last_error = :e WHERE id = :id")
                        .param("a", attempts).param("s", status).param("e", msg.length() > 500 ? msg.substring(0, 500) : msg)
                        .param("id", id).update();
                failed++;
                log.warn("Tally post failed for outbox {}: {}", id, msg);
            }
        }
        if (posted + failed > 0) {
            activity.log(tenantId, userId, "TALLY_SYNC",
                    posted + " voucher" + (posted == 1 ? "" : "s") + " posted to Tally" + (failed > 0 ? ", " + failed + " failed" : ""),
                    null, null);
        }
        return new Result(posted, failed);
    }

    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT30S")
    public void scheduled() {
        if (!props.tally().autoSync()) return;
        var tenants = db.sql("SELECT DISTINCT tenant_id FROM tally_outbox WHERE status = 'PENDING'").query(Long.class).list();
        for (Long t : tenants) {
            try {
                tx.execute(status -> sync(t, null)); // self-call: TransactionTemplate supplies the transaction
            } catch (Exception e) {
                log.error("Scheduled Tally sync failed for tenant {}", t, e);
            }
        }
    }
}
