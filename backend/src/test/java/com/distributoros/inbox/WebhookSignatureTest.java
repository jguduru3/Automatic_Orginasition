package com.distributoros.inbox;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {
    static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test void acceptsValidAndRejectsTampered() throws Exception {
        byte[] body = "{\"x\":\"नमस्ते\"}".getBytes(StandardCharsets.UTF_8);
        String sig = sign("s3cret", body);
        assertThat(WhatsAppWebhookController.validSignature("s3cret", body, sig)).isTrue();
        assertThat(WhatsAppWebhookController.validSignature("other", body, sig)).isFalse();
        assertThat(WhatsAppWebhookController.validSignature("s3cret", "{}".getBytes(), sig)).isFalse();
        assertThat(WhatsAppWebhookController.validSignature("s3cret", body, null)).isFalse();
        assertThat(WhatsAppWebhookController.validSignature("s3cret", body, "sha256=zz")).isFalse();
        assertThat(WhatsAppWebhookController.validSignature("", body, sig)).isFalse();
    }
}
