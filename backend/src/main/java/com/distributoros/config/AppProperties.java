package com.distributoros.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        WhatsApp whatsapp,
        Tally tally,
        Blob blob,
        Ai ai,
        boolean simulateEnabled,
        int loginRateLimitPer5min) {

    public record Jwt(String secret, int ttlHours) {}

    public record WhatsApp(String mode, String apiBase, String accessToken, String appSecret, String verifyToken,
                           String reminderTemplate, String templateLanguage, String dispatchTemplate, String poTemplate) {
        public boolean isCloud() { return "cloud".equalsIgnoreCase(mode); }
    }

    public record Tally(String mode, String url, String company, String salesLedger, String cgstLedger,
                        String sgstLedger, String bankLedger, boolean autoSync, int maxAttempts,
                        String purchaseLedger, String inputCgstLedger, String inputSgstLedger, String debtorsGroup) {
        public boolean isHttp() { return "http".equalsIgnoreCase(mode); }
    }

    /** Azure AI Speech (voice notes) and Azure AI Vision Read (photos of order lists). Blank = feature off. */
    public record Ai(String speechKey, String speechRegion, String speechLanguage, String visionEndpoint, String visionKey) {
        private static boolean set(String v) { return v != null && !v.isBlank() && !"unset".equals(v); }
        public boolean speechOn() { return set(speechKey) && set(speechRegion); }
        public boolean visionOn() { return set(visionKey) && set(visionEndpoint); }
    }

    public record Blob(String mode, String localDir, String connectionString, String accountUrl, String container) {
        public boolean isAzure() { return "azure".equalsIgnoreCase(mode); }
    }
}
