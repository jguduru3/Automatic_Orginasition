package com.distributoros.integrations;

public interface WhatsAppClient {
    /** Sends a text message. Returns the provider message id (or a synthetic one in mock mode). */
    String sendText(String phoneNumberId, String toPhone, String body);

    /** Downloads inbound media (voice notes, photos). Returns null in mock mode. */
    byte[] downloadMedia(String mediaId);
}
