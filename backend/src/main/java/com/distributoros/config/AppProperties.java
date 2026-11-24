package com.distributoros.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        WhatsApp whatsapp,
        Tally tally,
        Blob blob,
        boolean simulateEnabled,
        int loginRateLimitPer5min) {

    public record Jwt(String secret, int ttlHours) {}

    public record WhatsApp(String mode, String apiBase, String accessToken, String appSecret, String verifyToken) {
        public boolean isCloud() { return "cloud".equalsIgnoreCase(mode); }
    }

    public record Tally(String mode, String url, String company, String salesLedger, String cgstLedger,
                        String sgstLedger, String bankLedger, boolean autoSync, int maxAttempts) {
        public boolean isHttp() { return "http".equalsIgnoreCase(mode); }
    }

    public record Blob(String mode, String localDir, String connectionString, String accountUrl, String container) {
        public boolean isAzure() { return "azure".equalsIgnoreCase(mode); }
    }
}
