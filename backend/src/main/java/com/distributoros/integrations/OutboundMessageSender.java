package com.distributoros.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OutboundMessageSender {
    private static final Logger log = LoggerFactory.getLogger(OutboundMessageSender.class);
    private final WhatsAppClient client;
    private final JdbcClient db;
    private final String lang;

    public OutboundMessageSender(WhatsAppClient client, JdbcClient db, com.distributoros.config.AppProperties props) {
        this.client = client; this.db = db;
        String l = props.whatsapp().templateLanguage();
        this.lang = l == null || l.isBlank() ? "en" : l;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void send(OutboundMessage m) {
        try {
            String phoneId = db.sql("SELECT wa_phone_number_id FROM tenants WHERE id = :t")
                    .param("t", m.tenantId()).query(String.class).optional().orElse(null);
            if (phoneId == null) {
                log.warn("Tenant {} has no WhatsApp number configured; message {} not sent", m.tenantId(), m.messageId());
                return;
            }
            String waId = m.template() != null
                    ? client.sendTemplate(phoneId, m.toPhone(), m.template(), lang, m.templateParams())
                    : client.sendText(phoneId, m.toPhone(), m.body());
            if (waId != null) {
                db.sql("UPDATE messages SET wa_message_id = :w WHERE id = :id")
                        .param("w", waId).param("id", m.messageId()).update();
            }
        } catch (Exception e) {
            // Never fail the business action because WhatsApp is down; the message stays in the thread.
            log.error("WhatsApp send failed for message {}", m.messageId(), e);
        }
    }
}
