package com.distributoros.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class MockWhatsAppClient implements WhatsAppClient {
    private static final Logger log = LoggerFactory.getLogger(MockWhatsAppClient.class);

    @Override
    public String sendText(String phoneNumberId, String toPhone, String body) {
        log.info("[mock whatsapp] to={} chars={}", toPhone, body.length());
        return "mock-" + UUID.randomUUID();
    }

    @Override
    public byte[] downloadMedia(String mediaId) { return null; }
}
