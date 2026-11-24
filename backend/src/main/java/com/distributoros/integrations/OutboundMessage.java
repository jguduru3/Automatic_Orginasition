package com.distributoros.integrations;

/** Published inside a transaction; sent to WhatsApp only after the transaction commits. */
public record OutboundMessage(long tenantId, long messageId, String toPhone, String body,
                              String template, java.util.List<String> templateParams) {
    public OutboundMessage(long tenantId, long messageId, String toPhone, String body) {
        this(tenantId, messageId, toPhone, body, null, null);
    }
}
