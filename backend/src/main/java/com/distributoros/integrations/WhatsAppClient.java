package com.distributoros.integrations;

public interface WhatsAppClient {
    /** Sends a text message. Returns the provider message id (or a synthetic one in mock mode). */
    String sendText(String phoneNumberId, String toPhone, String body);

    /** Sends a pre-approved template (required outside the 24-hour customer-service window). */
    String sendTemplate(String phoneNumberId, String toPhone, String template, String language, java.util.List<String> params);

    /** Downloads inbound media (voice notes, photos). Returns null in mock mode. */
    byte[] downloadMedia(String mediaId);
}
