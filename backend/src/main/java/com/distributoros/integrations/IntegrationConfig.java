package com.distributoros.integrations;

import com.distributoros.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IntegrationConfig {

    @Bean
    WhatsAppClient whatsAppClient(AppProperties props, ObjectMapper json) {
        AppProperties.WhatsApp w = props.whatsapp();
        if (w.isCloud()) {
            if (w.accessToken() == null || w.accessToken().isBlank() || w.appSecret() == null || w.appSecret().isBlank()) {
                throw new IllegalStateException("WHATSAPP_MODE=cloud needs WHATSAPP_ACCESS_TOKEN and WHATSAPP_APP_SECRET");
            }
            return new CloudApiWhatsAppClient(w, json);
        }
        return new MockWhatsAppClient();
    }

    @Bean
    TallyClient tallyClient(AppProperties props) {
        return props.tally().isHttp() ? new HttpTallyClient(props.tally().url()) : new MockTallyClient();
    }
}
