package com.distributoros;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookIT extends IntegrationTestBase {
    static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    ResponseEntity<String> send(byte[] body, String sig) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (sig != null) h.set("X-Hub-Signature-256", sig);
        return rest.exchange("/api/webhooks/whatsapp", HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    @Test
    void verifiesAndDeduplicates() throws Exception {
        assertThat(rest.getForEntity("/api/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=test-verify&hub.challenge=42",
                String.class).getBody()).isEqualTo("42");
        assertThat(rest.getForEntity("/api/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=nope&hub.challenge=42",
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String phone = newPhone();
        String json = """
                {"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"demo-phone-id"},
                 "messages":[{"from":"%s","id":"wamid.%s","type":"text","text":{"body":"1.5 wire 2 coil चाहिए"}}]}}]}]}
                """.formatted(phone, phone);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        assertThat(send(body, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(body, "sha256=00").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(body, sign(body)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(send(body, sign(body)).getStatusCode()).isEqualTo(HttpStatus.OK); // retry from Meta

        long conv = conversationFor(phone);
        var messages = (java.util.List<?>) getMap(owner, "/api/inbox/" + conv).getBody().get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.toString()).contains("चाहिए");
    }
}
