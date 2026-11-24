package com.distributoros.common;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Gap-free per-tenant numbering. Must be called inside the caller's transaction. */
@Component
public class DocNumbers {
    private final JdbcClient db;

    public DocNumbers(JdbcClient db) { this.db = db; }

    public long next(long tenantId, String kind) {
        return db.sql("""
                INSERT INTO doc_counters (tenant_id, kind, next_value) VALUES (:t, :k, 2)
                ON CONFLICT (tenant_id, kind) DO UPDATE SET next_value = doc_counters.next_value + 1
                RETURNING next_value - 1
                """)
                .param("t", tenantId).param("k", kind)
                .query(Long.class).single();
    }
}
