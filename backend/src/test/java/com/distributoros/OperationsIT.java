package com.distributoros;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Godowns, transfers, purchases + goods receipt, pharma batches (FEFO, expiry, licences), Tally pull, GPS, signup. */
class OperationsIT extends IntegrationTestBase {

    @SuppressWarnings("unchecked")
    @Test
    void pharmaBatchesExpiryAndLicences() {
        String pharma = login("owner@pharma.demo.in");
        var customers = getList(pharma, "/api/customers").getBody();
        long valid = customers.stream().filter(c -> "Shree Ganesh Medical".equals(c.get("name"))).map(c -> id(c.get("id"))).findFirst().orElseThrow();
        long expired = customers.stream().filter(c -> "Lifeline Chemists".equals(c.get("name"))).map(c -> id(c.get("id"))).findFirst().orElseThrow();
        long pcm = id(product(pharma, "PCM650").get("id"));
        long amx = id(product(pharma, "AMX500").get("id"));

        // expired batch PC24A11 (120 units) is not sellable: free excludes it
        var item = getMap(pharma, "/api/inventory/" + pcm).getBody();
        var batches = (List<Map<String, Object>>) item.get("batches");
        assertThat(batches.get(0).get("batchNo")).isEqualTo("PC24A11");
        assertThat(batches.get(0).get("expired")).isEqualTo(true);

        // FEFO: an order for 650 takes all 600 of the earliest unexpired batch, then 50 from the next
        var r = post(pharma, "/api/orders", Map.of("customerId", valid, "source", "PHONE", "lines", List.of(Map.of("productId", pcm, "qty", 650))));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        var order = getMap(pharma, "/api/orders/" + id(r.getBody().get("id"))).getBody();
        String picked = (String) ((List<Map<String, Object>>) order.get("lines")).get(0).get("batches");
        assertThat(picked).contains("PC25B07").contains("x600").contains("PC25D19").contains("x50").doesNotContain("PC24A11");

        // prescription item to a chemist whose drug licence expired: refused; plain OTC item is fine
        var lic = post(pharma, "/api/orders", Map.of("customerId", expired, "source", "PHONE", "lines", List.of(Map.of("productId", amx, "qty", 2))));
        assertThat(lic.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(lic.getBody().get("code")).isEqualTo("LICENCE_INVALID");
        assertThat(post(pharma, "/api/orders", Map.of("customerId", expired, "source", "PHONE", "lines", List.of(Map.of("productId", pcm, "qty", 2))))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // near-expiry report lists the expired and soon-expiring batches
        var exp = getList(pharma, "/api/inventory/expiring?days=90").getBody();
        assertThat(exp).extracting(x -> x.get("batchNo")).contains("PC24A11", "PN25F01", "IN25H30");

        // dispatch consumes from the picked batches
        long vehicle = id(getList(pharma, "/api/orders/vehicles").getBody().get(0).get("id"));
        long oid = id(r.getBody().get("id"));
        post(pharma, "/api/orders/" + oid + "/pack", Map.of());
        assertThat(post(pharma, "/api/orders/" + oid + "/dispatch", Map.of("vehicleId", vehicle)).getBody().get("driverAlerted")).isEqualTo(true);
        var after = (List<Map<String, Object>>) getMap(pharma, "/api/inventory/" + pcm).getBody().get("batches");
        assertThat(after).noneMatch(b -> "PC25B07".equals(b.get("batchNo"))); // fully used
        // tenants are isolated
        assertThat(getMap(owner, "/api/inventory/" + pcm).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @SuppressWarnings("unchecked")
    @Test
    void purchaseOrderToGoodsReceiptWithBatches() {
        String pharma = login("owner@pharma.demo.in");
        long supplier = id(getList(pharma, "/api/suppliers").getBody().get(0).get("id"));
        long ins = id(product(pharma, "INS30").get("id"));
        long cold = getList(pharma, "/api/godowns").getBody().stream().filter(g -> "COLD".equals(g.get("code"))).map(g -> id(g.get("id"))).findFirst().orElseThrow();
        var po = post(pharma, "/api/purchases", Map.of("supplierId", supplier, "godownId", cold, "expectedDate", LocalDate.now().plusDays(2).toString(),
                "lines", List.of(Map.of("productId", ins, "qty", 50, "rate", 118))));
        assertThat(po.getStatusCode()).isEqualTo(HttpStatus.OK);
        long poId = id(po.getBody().get("id"));
        // cannot receive before sending
        long lineId = id(((List<Map<String, Object>>) getMap(pharma, "/api/purchases/" + poId).getBody().get("lines")).get(0).get("id"));
        assertThat(post(pharma, "/api/purchases/" + poId + "/receive", Map.of("lines", List.of(Map.of("poLineId", lineId, "qty", 10))))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(post(pharma, "/api/purchases/" + poId + "/send", Map.of()).getBody().get("messaged")).isEqualTo(true);
        // batch item needs batch + expiry
        assertThat(post(pharma, "/api/purchases/" + poId + "/receive", Map.of("lines", List.of(Map.of("poLineId", lineId, "qty", 10))))
                .getBody().get("code")).isEqualTo("BATCH_REQUIRED");
        var part = post(pharma, "/api/purchases/" + poId + "/receive", Map.of("supplierInvoiceNo", "MS/2231",
                "lines", List.of(Map.of("poLineId", lineId, "qty", 30, "batchNo", "IN27A01", "expiryDate", LocalDate.now().plusYears(1).toString()))));
        assertThat(part.getBody().get("status")).isEqualTo("PARTIAL");
        assertThat(post(pharma, "/api/purchases/" + poId + "/receive", Map.of("lines", List.of(Map.of("poLineId", lineId, "qty", 21,
                "batchNo", "IN27A01", "expiryDate", LocalDate.now().plusYears(1).toString())))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(pharma, "/api/purchases/" + poId + "/receive", Map.of("lines", List.of(Map.of("poLineId", lineId, "qty", 20,
                "batchNo", "IN27A01", "expiryDate", LocalDate.now().plusYears(1).toString())))).getBody().get("status")).isEqualTo("RECEIVED");
        var batches = (List<Map<String, Object>>) getMap(pharma, "/api/inventory/" + ins).getBody().get("batches");
        assertThat(batches).anyMatch(b -> "IN27A01".equals(b.get("batchNo")) && ((Number) b.get("onHand")).intValue() == 50);
        // purchase vouchers queued for Tally
        assertThat((List<Map<String, Object>>) getMap(pharma, "/api/tally").getBody().get("queue")).anyMatch(v -> "PURCHASE".equals(v.get("type")));
    }

    @Test
    void transfersSuggestionsAndTallyPull() {
        var godowns = getList(owner, "/api/godowns").getBody();
        long main = godowns.stream().filter(g -> Boolean.TRUE.equals(g.get("isDefault"))).map(g -> id(g.get("id"))).findFirst().orElseThrow();
        long branch = godowns.stream().filter(g -> !Boolean.TRUE.equals(g.get("isDefault"))).map(g -> id(g.get("id"))).findFirst().orElseThrow();
        long w15 = productId("W15");
        int before = ((Number) product("W15").get("onHand")).intValue();
        assertThat(post(owner, "/api/transfers", Map.of("fromGodownId", main, "toGodownId", branch,
                "lines", List.of(Map.of("productId", w15, "qty", 5)))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) product("W15").get("onHand")).intValue()).isEqualTo(before - 5);
        assertThat(post(owner, "/api/transfers", Map.of("fromGodownId", main, "toGodownId", main,
                "lines", List.of(Map.of("productId", w15, "qty", 5)))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(owner, "/api/transfers", Map.of("fromGodownId", branch, "toGodownId", main,
                "lines", List.of(Map.of("productId", w15, "qty", 999999)))).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(getList(owner, "/api/purchases/suggestions").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getList(owner, "/api/purchases?status=ALL").getBody()).isNotEmpty();

        var pull = post(owner, "/api/tally/pull", Map.of("applyStock", true));
        assertThat(pull.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) pull.getBody().get("itemsMatched")).intValue()).isGreaterThanOrEqualTo(2);
        assertThat(((Number) pull.getBody().get("customersMatched")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(getMap(owner, "/api/tally").getBody().get("lastPullSummary")).isNotNull();
        // second pull is idempotent: nothing new created
        var again = post(owner, "/api/tally/pull", Map.of("applyStock", true)).getBody();
        assertThat(((Number) again.get("itemsCreated")).intValue()).isZero();
        assertThat(((Number) again.get("customersCreated")).intValue()).isZero();
    }

    @Test
    void liveLocationAndSignup() {
        String sales = login("amit@demo.in");
        assertThat(post(sales, "/api/field/location", Map.of("lat", 18.52, "lng", 73.85, "accuracy", 12)).getBody().get("stored")).isEqualTo(true);
        assertThat(post(sales, "/api/field/location", Map.of("lat", 18.53, "lng", 73.86)).getBody().get("stored")).isEqualTo(false); // < 30 s
        assertThat(post(sales, "/api/field/location", Map.of("lat", 123, "lng", 73.86)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(owner, "/api/field/location", Map.of("lat", 18.5, "lng", 73.8)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getList(owner, "/api/field/live").getBody()).anyMatch(p -> "Amit Jadhav".equals(p.get("name")));
        long amit = getList(owner, "/api/field/live").getBody().stream().filter(p -> "Amit Jadhav".equals(p.get("name")))
                .map(p -> id(p.get("userId"))).findFirst().orElseThrow();
        assertThat(getList(owner, "/api/field/trail/" + amit).getBody()).isNotEmpty();
        assertThat(getList(sales, "/api/field/live").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String email = "founder" + newPhone() + "@new.example";
        var s = rest.postForEntity("/api/signup", Map.of("businessName", "New Traders", "ownerName", "Founder", "email", email,
                "password", "Start@2026"), Map.class);
        assertThat(s.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) s.getBody().get("token");
        assertThat(getList(token, "/api/godowns").getBody()).hasSize(1);
        assertThat(getList(token, "/api/customers").getBody()).isEmpty(); // fresh, isolated tenant
        assertThat(rest.postForEntity("/api/signup", Map.of("businessName", "Dup", "ownerName", "X", "email", email,
                "password", "Start@2026"), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
