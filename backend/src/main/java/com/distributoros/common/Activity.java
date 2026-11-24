package com.distributoros.common;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class Activity {
    private final JdbcClient db;

    public Activity(JdbcClient db) { this.db = db; }

    public void log(long tenantId, Long userId, String action, String message, String entity, Long entityId) {
        db.sql("""
                INSERT INTO activity_log (tenant_id, user_id, action, message, entity, entity_id)
                VALUES (:t, :u, :a, :m, :e, :id)
                """)
                .param("t", tenantId).param("u", userId).param("a", action).param("m", message)
                .param("e", entity).param("id", entityId)
                .update();
    }
}
