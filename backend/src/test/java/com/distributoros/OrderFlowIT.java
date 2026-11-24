package com.distributoros;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrderFlowIT extends IntegrationTestBase {

    @Test
    void whatsappToTallyHappyPath() {
        var c = newCustomer("A", 10_000_000);
        String phone = (String) c.get("phone");
        int freeBefore = ((Number) product("W15").get("free")).intValue();

        assertThat(post(owner, "/api/inbox/simulate", Map.of("phone", phone, "text", "1.5 wire - 3 coil\n32A MCB 10 pcs"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        long conv = conversationFor(phone);

        var draft = getMap(owner, "/api/inbox/" + conv + "/draft").getBody();
        @SuppressWarnings("unchecked") var lines = (List<Map<String, Object>>) draft.get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("sku")).isEqualTo("W15");
        assertThat(new BigDecimal(lines.get(0).get("rate").toString())).isEqualByComparingTo("1334"); // 1450 less 8%

        var confirm = post(owner, "/api/inbox/" + conv + "/confirm", Map.of("lines", List.of(
                Map.of("productId", lines.get(0).get("productId"), "qty", 3),
                Map.of("productId", lines.get(1).get("productId"), "qty", 10))));
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.OK);
        long orderId = id(confirm.getBody().get("id"));
        assertThat(((Number) product("W15").get("free")).intValue()).isEqualTo(freeBefore - 3);

        // double confirm is rejected
        assertThat(post(owner, "/api/inbox/" + conv + "/confirm", Map.of("lines", List.of(
                Map.of("productId", lines.get(0).get("productId"), "qty", 1)))).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // reply was posted in the thread
        @SuppressWarnings("unchecked") var msgs = (List<Map<String, Object>>) getMap(owner, "/api/inbox/" + conv).getBody().get("messages");
        assertThat(msgs.get(msgs.size() - 1).get("direction")).isEqualTo("OUT");

        // dispatch before pack is refused
        long vehicle = id(getList(owner, "/api/orders/vehicles").getBody().get(0).get("id"));
        assertThat(post(owner, "/api/orders/" + orderId + "/dispatch", Map.of("vehicleId", vehicle)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(post(owner, "/api/orders/" + orderId + "/pack", Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        var disp = post(owner, "/api/orders/" + orderId + "/dispatch", Map.of("vehicleId", vehicle));
        assertThat(disp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) disp.getBody().get("invoice")).startsWith("INV-");

        // deliver with a photo proof (tiny valid PNG header)
        var form = new LinkedMultiValueMap<String, Object>();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.IMAGE_PNG);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0};
        form.add("pod", new HttpEntity<>(new ByteArrayResource(png) { @Override public String getFilename() { return "pod.png"; } }, fileHeaders));
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(owner);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        var del = rest.exchange("/api/orders/" + orderId + "/deliver", HttpMethod.POST, new HttpEntity<>(form, h), MAP);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.OK);
        var order = getMap(owner, "/api/orders/" + orderId).getBody();
        assertThat(order.get("status")).isEqualTo("DELIVERED");
        assertThat((String) order.get("podPath")).isNotBlank();
        var file = rest.exchange("/api/files?path=" + order.get("podPath"), HttpMethod.GET, new HttpEntity<>(auth(owner)), byte[].class);
        assertThat(file.getBody()).isEqualTo(png);

        // payment, idempotent on retry
        Map<String, Object> pay = Map.of("customerId", c.get("customerId"), "amount", 5000, "mode", "UPI",
                "idempotencyKey", "it-" + phone);
        var p1 = post(owner, "/api/payments", pay);
        var p2 = post(owner, "/api/payments", pay);
        assertThat(p1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(p2.getBody().get("receipt")).isEqualTo(p1.getBody().get("receipt"));
        assertThat(p2.getBody().get("duplicate")).isEqualTo(true);

        // tally sync posts the invoice and the receipt
        var accountant = login("accounts@demo.in");
        var sync = post(accountant, "/api/tally/sync", Map.of());
        assertThat(((Number) sync.getBody().get("posted")).intValue()).isGreaterThanOrEqualTo(2);
        assertThat((List<?>) getMap(accountant, "/api/tally").getBody().get("queue")).isEmpty();
    }

    @Test
    void creditHoldNeedsOwnerOverride() {
        var c = newCustomer("C", 1000);
        String phone = (String) c.get("phone");
        post(owner, "/api/inbox/simulate", Map.of("phone", phone, "text", "1.5 wire 5 coil"));
        long conv = conversationFor(phone);
        var body = Map.of("lines", List.of(Map.of("productId", productId("W15"), "qty", 5)));

        var held = post(owner, "/api/inbox/" + conv + "/confirm", body);
        assertThat(held.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(held.getBody().get("code")).isEqualTo("CREDIT_HOLD");

        String sales = login("amit@demo.in");
        var notAllowed = post(sales, "/api/inbox/" + conv + "/confirm", Map.of("lines", body.get("lines"), "overrideCredit", true));
        assertThat(notAllowed.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        var ok = post(owner, "/api/inbox/" + conv + "/confirm", Map.of("lines", body.get("lines"), "overrideCredit", true));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shortStockBecomesBackorderAndCancelReleasesIt() {
        var c = newCustomer("A", 100_000_000);
        int free = ((Number) product("FEX").get("free")).intValue();
        var r = post(owner, "/api/orders", Map.of("customerId", c.get("customerId"), "source", "PHONE",
                "lines", List.of(Map.of("productId", productId("FEX"), "qty", free + 5))));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) r.getBody().get("backorders")).hasSize(1);
        assertThat(((Number) product("FEX").get("free")).intValue()).isZero();
        assertThat(post(owner, "/api/orders/" + id(r.getBody().get("id")) + "/cancel", Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) product("FEX").get("free")).intValue()).isEqualTo(free);
    }

    @Test
    void validationAndAuth() {
        assertThat(rest.getForEntity("/api/dashboard", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.postForEntity("/api/auth/login", Map.of("email", "owner@demo.in", "password", "wrong"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var bad = post(owner, "/api/orders", Map.of("customerId", 1, "source", "PHONE", "lines", List.of(Map.of("productId", 1, "qty", 0))));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var badStatus = getList(owner, "/api/orders?status=DROP%20TABLE");
        assertThat(badStatus.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String driver = login("raju@demo.in");
        assertThat(post(driver, "/api/payments", Map.of("customerId", 1, "amount", 1, "mode", "UPI")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getMap(driver, "/api/tally").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getMap(owner, "/api/files?path=tenants/999/pod/x.png").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(owner, "/api/products/" + productId("W15") + "/stock", Map.of("delta", -100000, "reason", "x"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void dashboardAndListsRespond() {
        for (String url : List.of("/api/dashboard", "/api/field", "/api/tally", "/api/me")) {
            assertThat(getMap(owner, url).getStatusCode()).as(url).isEqualTo(HttpStatus.OK);
        }
        for (String url : List.of("/api/inbox", "/api/orders?status=ALL", "/api/orders/summary", "/api/inventory",
                "/api/customers", "/api/customers?q=patil", "/api/collections", "/api/payments/today", "/api/users",
                "/api/customers/tiers", "/api/orders/vehicles")) {
            assertThat(getList(owner, url).getStatusCode()).as(url).isEqualTo(HttpStatus.OK);
        }
    }
}
