package com.distributoros.integrations;

/** Published inside a transaction; sent to WhatsApp only after the transaction commits. */
public record OutboundMessage(long tenantId, long messageId, String toPhone, String body) {}
