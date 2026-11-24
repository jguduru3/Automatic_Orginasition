package com.distributoros.integrations;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TallyPrime XML over HTTP (Tally's built-in server, default port 9000). In production the Tally PC is
 * usually behind NAT: expose it through a small on-prem relay or VPN and set TALLY_URL accordingly.
 */
public class HttpTallyClient implements TallyClient {
    private static final Pattern CREATED = Pattern.compile("<CREATED>(\\d+)</CREATED>");
    private static final Pattern ERRORS = Pattern.compile("<ERRORS>(\\d+)</ERRORS>");
    private static final Pattern LASTVCH = Pattern.compile("<LASTVCHID>(\\d+)</LASTVCHID>");
    private static final Pattern LINEERROR = Pattern.compile("<LINEERROR>(.*?)</LINEERROR>", Pattern.DOTALL);

    private final RestClient http;

    public HttpTallyClient(String url) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(30_000);
        this.http = RestClient.builder().baseUrl(url).requestFactory(f).build();
    }

    @Override
    public String post(String xmlEnvelope, String fallbackNumber) {
        String resp = http.post().contentType(MediaType.TEXT_XML).body(xmlEnvelope).retrieve().body(String.class);
        if (resp == null) throw new IllegalStateException("Empty response from Tally");
        Matcher err = ERRORS.matcher(resp);
        if (err.find() && Integer.parseInt(err.group(1)) > 0) {
            Matcher le = LINEERROR.matcher(resp);
            throw new IllegalStateException("Tally rejected voucher: " + (le.find() ? le.group(1).trim() : "unknown error"));
        }
        Matcher c = CREATED.matcher(resp);
        if (!c.find() || Integer.parseInt(c.group(1)) < 1) {
            throw new IllegalStateException("Tally did not create the voucher");
        }
        Matcher id = LASTVCH.matcher(resp);
        return id.find() ? fallbackNumber + " (id " + id.group(1) + ")" : fallbackNumber;
    }
}
