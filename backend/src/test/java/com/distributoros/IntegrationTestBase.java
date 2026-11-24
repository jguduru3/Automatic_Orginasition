package com.distributoros;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static { PG.start(); }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    private static final AtomicLong PHONE = new AtomicLong(919700000000L);
    protected static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    protected static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};

    @Autowired protected TestRestTemplate rest;
    protected String owner;

    @BeforeEach
    void loginOwner() { owner = login("owner@demo.in"); }

    protected String login(String email) {
        var res = rest.postForEntity("/api/auth/login", Map.of("email", email, "password", "Demo@1234"), Map.class);
        if (res.getStatusCode() != HttpStatus.OK) throw new IllegalStateException("login failed " + res.getStatusCode());
        return (String) res.getBody().get("token");
    }

    protected HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    protected ResponseEntity<Map<String, Object>> post(String token, String url, Object body) {
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, auth(token)), MAP);
    }

    protected ResponseEntity<Map<String, Object>> getMap(String token, String url) {
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(auth(token)), MAP);
    }

    protected ResponseEntity<List<Map<String, Object>>> getList(String token, String url) {
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(auth(token)), LIST);
    }

    protected static long id(Object o) { return ((Number) o).longValue(); }

    protected String newPhone() { return Long.toString(PHONE.incrementAndGet()); }

    /** Creates a customer with its own phone and returns {customerId, phone}. */
    protected Map<String, Object> newCustomer(String tier, long limit) {
        String phone = newPhone();
        var r = post(owner, "/api/customers", Map.of("name", "Test Retailer " + phone, "town", "Pune", "phone", phone,
                "tierCode", tier, "creditLimit", limit));
        if (r.getStatusCode() != HttpStatus.OK) throw new IllegalStateException("customer create failed: " + r.getBody());
        return Map.of("customerId", id(r.getBody().get("id")), "phone", phone);
    }

    protected long productId(String sku) {
        return getList(owner, "/api/inventory").getBody().stream().filter(p -> sku.equals(p.get("sku")))
                .map(p -> id(p.get("id"))).findFirst().orElseThrow();
    }

    protected Map<String, Object> product(String sku) {
        return getList(owner, "/api/inventory").getBody().stream().filter(p -> sku.equals(p.get("sku"))).findFirst().orElseThrow();
    }

    protected long conversationFor(String phone) {
        return getList(owner, "/api/inbox").getBody().stream().filter(c -> phone.equals(c.get("phone")))
                .map(c -> id(c.get("id"))).findFirst().orElseThrow();
    }

    protected void addStock(String sku, int delta) {
        var r = post(owner, "/api/products/" + productId(sku) + "/stock", Map.of("delta", delta, "reason", "test"));
        if (r.getStatusCode() != HttpStatus.OK) throw new IllegalStateException("stock adjust failed " + r.getBody());
    }
}
