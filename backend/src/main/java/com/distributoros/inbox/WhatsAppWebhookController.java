package com.distributoros.inbox;

import com.distributoros.config.AppProperties;
import com.distributoros.integrations.WhatsAppClient;
import com.distributoros.storage.BlobStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Meta WhatsApp Cloud API webhook: GET for verification, POST for messages (HMAC-SHA256 signed). */
@RestController
@RequestMapping("/api/webhooks/whatsapp")
public class WhatsAppWebhookController {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    private final AppProperties props;
    private final InboxService inbox;
    private final JdbcClient db;
    private final ObjectMapper json;
    private final WhatsAppClient wa;
    private final BlobStorage blobs;

    public WhatsAppWebhookController(AppProperties props, InboxService inbox, JdbcClient db, ObjectMapper json,
                                     WhatsAppClient wa, BlobStorage blobs) {
        this.props = props; this.inbox = inbox; this.db = db; this.json = json; this.wa = wa; this.blobs = blobs;
    }

    @GetMapping
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                         @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        String expected = props.whatsapp().verifyToken();
        if ("subscribe".equals(mode) && expected != null && !expected.isBlank() && expected.equals(token) && challenge != null) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
                                        @RequestBody(required = false) byte[] body) {
        if (body == null || body.length > 1_000_000) return ResponseEntity.badRequest().build();
        if (!validSignature(props.whatsapp().appSecret(), body, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            JsonNode root = json.readTree(body);
            for (JsonNode entry : root.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    JsonNode value = change.path("value");
                    String phoneId = value.path("metadata").path("phone_number_id").asText("");
                    Long tenantId = db.sql("SELECT id FROM tenants WHERE wa_phone_number_id = :p").param("p", phoneId)
                            .query(Long.class).optional().orElse(null);
                    if (tenantId == null) {
                        log.warn("Webhook for unknown phone_number_id {}", phoneId);
                        continue;
                    }
                    for (JsonNode m : value.path("messages")) handleMessage(tenantId, m);
                }
            }
        } catch (Exception e) {
            // Always 200 after a valid signature so Meta does not retry forever; the error is logged.
            log.error("Webhook processing failed", e);
        }
        return ResponseEntity.ok().build();
    }

    private void handleMessage(long tenantId, JsonNode m) {
        String type = m.path("type").asText("");
        String from = m.path("from").asText("");
        String id = m.path("id").asText(null);
        String text;
        String kind = "TEXT";
        String blob = null;
        switch (type) {
            case "text" -> text = m.path("text").path("body").asText("");
            case "audio" -> {
                kind = "VOICE";
                text = "[Voice note received - open to listen]";
                blob = saveMedia(tenantId, m.path("audio").path("id").asText(null), "ogg");
            }
            case "image" -> {
                kind = "IMAGE";
                text = m.path("image").path("caption").asText("[Photo of order list]");
                blob = saveMedia(tenantId, m.path("image").path("id").asText(null), "jpg");
            }
            default -> { return; }
        }
        inbox.receive(tenantId, new InboxService.Inbound(id, from, text, kind, blob));
    }

    private String saveMedia(long tenantId, String mediaId, String ext) {
        if (mediaId == null) return null;
        try {
            byte[] data = wa.downloadMedia(mediaId);
            if (data == null) return null;
            String path = "tenants/" + tenantId + "/media/" + UUID.randomUUID() + "." + ext;
            blobs.put(path, data, "ogg".equals(ext) ? "audio/ogg" : "image/jpeg");
            return path;
        } catch (Exception e) {
            log.warn("Could not store media {}", mediaId, e);
            return null;
        }
    }

    static boolean validSignature(String secret, byte[] body, String header) {
        if (secret == null || secret.isBlank() || header == null || !header.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] given = HexFormat.of().parseHex(header.substring(7));
            return MessageDigest.isEqual(expected, given);
        } catch (Exception e) {
            return false;
        }
    }
}
