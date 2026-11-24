package com.distributoros.integrations;

import com.distributoros.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns voice notes and photos of handwritten order lists into text, so the normal order parser can read them.
 * Speech: Azure AI Speech short-audio REST (WhatsApp voice notes are OGG/Opus, up to 60 s).
 * Photos: Azure AI Vision Image Analysis 4.0 "read". Both return null when not configured.
 */
@Component
public class MediaReader {
    private static final Logger log = LoggerFactory.getLogger(MediaReader.class);
    private final AppProperties.Ai cfg;
    private final ObjectMapper json;
    private final RestClient http;

    public MediaReader(AppProperties props, ObjectMapper json) {
        this.cfg = props.ai();
        this.json = json;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(30_000);
        this.http = RestClient.builder().requestFactory(f).build();
    }

    public boolean canTranscribe() { return cfg != null && cfg.speechOn(); }
    public boolean canRead() { return cfg != null && cfg.visionOn(); }

    public String transcribe(byte[] oggOpus) {
        if (!canTranscribe() || oggOpus == null || oggOpus.length == 0) return null;
        String lang = cfg.speechLanguage() == null || cfg.speechLanguage().isBlank() ? "hi-IN" : cfg.speechLanguage();
        String url = "https://" + cfg.speechRegion() + ".stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1"
                + "?language=" + lang + "&format=simple";
        String resp = http.post().uri(java.net.URI.create(url))
                .header("Ocp-Apim-Subscription-Key", cfg.speechKey())
                .contentType(MediaType.parseMediaType("audio/ogg; codecs=opus"))
                .body(oggOpus).retrieve().body(String.class);
        return parseSpeech(json, resp);
    }

    static String parseSpeech(ObjectMapper json, String resp) {
        try {
            JsonNode n = json.readTree(resp);
            if (!"Success".equals(n.path("RecognitionStatus").asText())) return null;
            String t = n.path("DisplayText").asText("").trim();
            return t.isEmpty() ? null : t;
        } catch (Exception e) {
            log.warn("Unreadable speech response");
            return null;
        }
    }

    public String readImage(byte[] image) {
        if (!canRead() || image == null || image.length == 0) return null;
        String base = cfg.visionEndpoint().endsWith("/") ? cfg.visionEndpoint() : cfg.visionEndpoint() + "/";
        String resp = http.post().uri(java.net.URI.create(base + "computervision/imageanalysis:analyze?api-version=2023-10-01&features=read"))
                .header("Ocp-Apim-Subscription-Key", cfg.visionKey())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(image).retrieve().body(String.class);
        return parseRead(json, resp);
    }

    static String parseRead(ObjectMapper json, String resp) {
        try {
            List<String> lines = new ArrayList<>();
            for (JsonNode block : json.readTree(resp).path("readResult").path("blocks")) {
                for (JsonNode line : block.path("lines")) {
                    String t = line.path("text").asText("").trim();
                    if (!t.isEmpty()) lines.add(t);
                }
            }
            return lines.isEmpty() ? null : String.join("\n", lines);
        } catch (Exception e) {
            log.warn("Unreadable vision response");
            return null;
        }
    }
}
