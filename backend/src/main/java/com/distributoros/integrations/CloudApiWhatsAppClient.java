package com.distributoros.integrations;

import com.distributoros.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Meta WhatsApp Cloud API. Docs: POST /{phone-number-id}/messages, GET /{media-id}. */
public class CloudApiWhatsAppClient implements WhatsAppClient {
    private final RestClient http;
    private final String token;
    private final ObjectMapper json;

    public CloudApiWhatsAppClient(AppProperties.WhatsApp cfg, ObjectMapper json) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(15_000);
        this.http = RestClient.builder().baseUrl(cfg.apiBase()).requestFactory(f).build();
        this.token = cfg.accessToken();
        this.json = json;
    }

    @Override
    public String sendText(String phoneNumberId, String toPhone, String body) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "recipient_type", "individual",
                "to", toPhone,
                "type", "text",
                "text", Map.of("preview_url", false, "body", body.length() > 4000 ? body.substring(0, 4000) : body));
        String resp = http.post().uri("/{id}/messages", phoneNumberId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve().body(String.class);
        try {
            JsonNode n = json.readTree(resp);
            return n.path("messages").path(0).path("id").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public byte[] downloadMedia(String mediaId) {
        String meta = http.get().uri("/{id}", mediaId).header("Authorization", "Bearer " + token)
                .retrieve().body(String.class);
        try {
            String url = json.readTree(meta).path("url").asText();
            return RestClient.create().get().uri(java.net.URI.create(url))
                    .header("Authorization", "Bearer " + token).retrieve().body(byte[].class);
        } catch (Exception e) {
            throw new IllegalStateException("Media download failed", e);
        }
    }
}
