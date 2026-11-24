package com.distributoros;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Hammers the paths where money and stock can go wrong under concurrency. */
class ConcurrencyIT extends IntegrationTestBase {

    @Test
    void neverOversellsUnderConcurrentOrders() throws Exception {
        addStock("DB8", 20);
        int free = ((Number) product("DB8").get("free")).intValue();
        long pid = productId("DB8");
        List<Map<String, Object>> customers = new ArrayList<>();
        for (int i = 0; i < 10; i++) customers.add(newCustomer("B", 100_000_000));

        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reservedUnits = new AtomicInteger(), errors = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            var c = customers.get(i % customers.size());
            fs.add(pool.submit(() -> {
                start.await();
                var r = post(owner, "/api/orders", Map.of("customerId", c.get("customerId"), "source", "PHONE",
                        "lines", List.of(Map.of("productId", pid, "qty", 2))));
                if (r.getStatusCode() == HttpStatus.OK) {
                    int back = ((List<?>) r.getBody().get("backorders")).isEmpty() ? 0 : 1;
                    reservedUnits.addAndGet(back == 0 ? 2 : 0);
                } else if (r.getStatusCode() != HttpStatus.CONFLICT) {
                    errors.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        var p = product("DB8");
        assertThat(errors.get()).isZero();
        assertThat(((Number) p.get("free")).intValue()).isGreaterThanOrEqualTo(0);
        assertThat(((Number) p.get("reserved")).intValue()).isLessThanOrEqualTo(((Number) p.get("onHand")).intValue());
        assertThat(reservedUnits.get()).isLessThanOrEqualTo(free);
    }

    @Test
    void concurrentDuplicatePaymentsCountOnce() throws Exception {
        var c = newCustomer("A", 1_000_000);
        String key = "dup-" + c.get("phone");
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> fs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                var r = post(owner, "/api/payments", Map.of("customerId", c.get("customerId"), "amount", 1000,
                        "mode", "CASH", "idempotencyKey", key));
                return r.getStatusCode() == HttpStatus.OK ? r.getBody().get("receipt") : r.getStatusCode();
            }));
        }
        start.countDown();
        java.util.Set<Object> receipts = new java.util.HashSet<>();
        for (Future<Object> f : fs) receipts.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(receipts).hasSize(1);
    }

    @Test
    void concurrentConfirmOfSameMessageCreatesOneOrder() throws Exception {
        var c = newCustomer("A", 100_000_000);
        String phone = (String) c.get("phone");
        post(owner, "/api/inbox/simulate", Map.of("phone", phone, "text", "32A MCB 2 pcs"));
        long conv = conversationFor(phone);
        long pid = productId("M32");
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                var r = post(owner, "/api/inbox/" + conv + "/confirm", Map.of("lines", List.of(Map.of("productId", pid, "qty", 2))));
                if (r.getStatusCode() == HttpStatus.OK) ok.incrementAndGet();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(ok.get()).isEqualTo(1);
    }
}
